package it.ingdati.lucene;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

/**
 * Programma 3: valutazione dell'efficacia del motore di ricerca.
 *
 * Il sistema, come il Ricercatore, mostra all'utente i primi K = 10 risultati. Su questi si calcola, per ogni query:
 *   Precision   = rilevanti tra i risultati mostrati / risultati mostrati (al massimo 10)
 *   Recall      = rilevanti tra i risultati mostrati / rilevanti totali
 *   F1          = 2 * P * R / (P + R)
 *   R-Precision = precision sui primi R risultati, con R = numero di rilevanti della query
 *                 (non penalizza le query con pochi rilevanti, a differenza di P@5 e P@10)
 *   P@5, P@10   = rilevanti tra i primi 5 / 10 risultati, diviso 5 / 10 (definizione standard)
 * e la media su tutte le query (macro-media), separatamente per il set A, il set B e in totale.
 *
 * Oltre al sistema del progetto prova altre configurazioni per giustificare ogni scelta con i numeri:
 *   - analyzer : progetto | nome-senza-stemming | contenuto-senza-folding | standard (StandardAnalyzer ovunque)
 *   - ranking  : BM25 (default di Lucene) | TF-IDF (ClassicSimilarity) | LM-Dirichlet
 *   - operatore: AND (sistema) | OR
 *
 * Uso:  mvn -q compile exec:java@valuta
 *       mvn -q compile exec:java@valuta -Dexec.args="--pool 20"    (genera i candidati da giudicare)
 *
 * File in valutazione/:  query.tsv, qrels.txt (input)  ->  riepilogo.csv, per_query.csv, dettaglio_sistema.txt (output)
 */
public class Valutatore {

    static final int K = 10;   // risultati mostrati all'utente
    static final Path CARTELLA = Paths.get("valutazione");

    record QueryTest(String id, String campo, String testo, String esigenza) {
        String set() { return id.substring(0, 1); }   // "A" o "B"
    }

    record Metriche(int rilevanti, int trovati, int rilMostrati, double p, double r, double f1,
                    double rprec, double p5, double p10) { }

    record Config(String analyzer, String ranking, QueryParser.Operator operatore) {
        String nome() { return analyzer + " / " + ranking + " / " + operatore; }
    }

    static final Config SISTEMA = new Config("progetto", "BM25", QueryParser.Operator.AND);

    public static void main(String[] args) throws Exception {
        Console.usaUtf8();
        Locale.setDefault(Locale.ITALY);
        Path docs = Paths.get("docs");

        List<QueryTest> queries = leggiQuery(CARTELLA.resolve("query.tsv"));
        System.out.printf("Query di test: %d (set A: %d, set B: %d)%n", queries.size(),
                queries.stream().filter(q -> q.set().equals("A")).count(),
                queries.stream().filter(q -> q.set().equals("B")).count());

        // Un indice in memoria per ciascuna scelta di analyzer
        Map<String, Directory> indici = new LinkedHashMap<>();
        for (String a : new String[] {"progetto", "nome-senza-stemming", "contenuto-senza-folding", "standard"})
            indici.put(a, costruisciIndice(docs, analyzerIndice(a)));

        List<Config> configurazioni = new ArrayList<>();
        for (String a : indici.keySet())
            for (String r : new String[] {"BM25", "TF-IDF", "LM-Dirichlet"})
                for (QueryParser.Operator o : new QueryParser.Operator[] {QueryParser.Operator.AND, QueryParser.Operator.OR})
                    configurazioni.add(new Config(a, r, o));

        if (args.length > 0 && args[0].equals("--pool")) {
            creaPool(queries, configurazioni, indici, args.length > 1 ? Integer.parseInt(args[1]) : 20);
            return;
        }

        Map<String, Set<String>> qrels = leggiQrels(CARTELLA.resolve("qrels.txt"));
        Map<Config, Map<String, Metriche>> tutti = new LinkedHashMap<>();
        Map<Config, Double> tempi = new LinkedHashMap<>();

        try (PrintWriter perQuery = new PrintWriter(Files.newBufferedWriter(CARTELLA.resolve("per_query.csv"), StandardCharsets.UTF_8));
             PrintWriter dettaglio = new PrintWriter(Files.newBufferedWriter(CARTELLA.resolve("dettaglio_sistema.txt"), StandardCharsets.UTF_8))) {

            perQuery.println("configurazione;query;set;campo;testo;rilevanti;trovati;rilevanti_mostrati;precision;recall;f1;r_precision;p@5;p@10");
            for (Config c : configurazioni) {
                Map<String, Metriche> ris = new LinkedHashMap<>();
                double tempoTotale = 0;
                try (IndexReader reader = DirectoryReader.open(indici.get(c.analyzer()))) {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    searcher.setSimilarity(similarita(c.ranking()));
                    StoredFields sf = searcher.storedFields();
                    for (QueryTest q : queries) {
                        Query query = parse(q, c);
                        searcher.search(query, reader.maxDoc());                 // riscaldamento
                        long t0 = System.nanoTime();
                        ScoreDoc[] hits = null;
                        for (int i = 0; i < 5; i++) hits = searcher.search(query, reader.maxDoc()).scoreDocs;
                        tempoTotale += (System.nanoTime() - t0) / 5 / 1_000_000.0;

                        List<String> classifica = new ArrayList<>();
                        for (ScoreDoc sd : hits) classifica.add(sf.document(sd.doc).get(Analizzatori.CAMPO_FILE));
                        Set<String> rel = qrels.getOrDefault(q.id(), Set.of());
                        Metriche m = calcola(classifica, rel);
                        ris.put(q.id(), m);
                        perQuery.printf(Locale.ROOT, "%s;%s;%s;%s;%s;%d;%d;%d;%.4f;%.4f;%.4f;%.4f;%.4f;%.4f%n",
                                c.nome(), q.id(), q.set(), q.campo(), q.testo().replace(";", ","), m.rilevanti(), m.trovati(),
                                m.rilMostrati(), m.p(), m.r(), m.f1(), m.rprec(), m.p5(), m.p10());
                        if (c.equals(SISTEMA)) scriviDettaglio(dettaglio, q, query, classifica, rel, m);
                    }
                }
                tutti.put(c, ris);
                tempi.put(c, tempoTotale / queries.size());
            }
        }

        // ---- stampa: sistema del progetto, query per query
        System.out.println("\nSISTEMA DEL PROGETTO: " + SISTEMA.nome() + "   (P, R, F1 sui primi " + K + " risultati mostrati)");
        stampaTabellaQuery(queries, tutti.get(SISTEMA));

        // ---- stampa e CSV: medie per configurazione e per set
        try (PrintWriter riepilogo = new PrintWriter(Files.newBufferedWriter(CARTELLA.resolve("riepilogo.csv"), StandardCharsets.UTF_8))) {
            riepilogo.println("configurazione;set;precision;recall;f1;r_precision;p@5;p@10;tempo_medio_ms");
            for (String set : new String[] {"A+B", "A", "B"}) {
                System.out.printf("%nMEDIE - set %s%n%-46s %6s %6s %6s %6s %6s %6s %8s%n", set,
                        "configurazione", "P", "R", "F1", "R-Prec", "P@5", "P@10", "ms/query");
                for (Config c : configurazioni) {
                    List<Metriche> ms = new ArrayList<>();
                    for (QueryTest q : queries) if (set.contains(q.set())) ms.add(tutti.get(c).get(q.id()));
                    double[] v = {media(ms, Metriche::p), media(ms, Metriche::r), media(ms, Metriche::f1),
                                  media(ms, Metriche::rprec), media(ms, Metriche::p5), media(ms, Metriche::p10)};
                    System.out.printf("%-46s %6.3f %6.3f %6.3f %6.3f %6.3f %6.3f %8.2f%s%n", c.nome(),
                            v[0], v[1], v[2], v[3], v[4], v[5], tempi.get(c), c.equals(SISTEMA) ? "  <- sistema" : "");
                    riepilogo.printf(Locale.ROOT, "%s;%s;%.4f;%.4f;%.4f;%.4f;%.4f;%.4f;%.3f%n",
                            c.nome(), set, v[0], v[1], v[2], v[3], v[4], v[5], tempi.get(c));
                }
            }
        }
        System.out.println("\nFile scritti in " + CARTELLA.toAbsolutePath() + ": riepilogo.csv, per_query.csv, dettaglio_sistema.txt");
    }

    // ------------------------------------------------------------------ metriche

    /** Confronta la classifica restituita (tutti i documenti trovati, in ordine) con l'insieme dei rilevanti. */
    static Metriche calcola(List<String> classifica, Set<String> rilevanti) {
        int nRel = rilevanti.size();
        int mostrati = Math.min(K, classifica.size());
        int relMostrati = contaRilevanti(classifica, rilevanti, K);
        double p = mostrati == 0 ? 0 : (double) relMostrati / mostrati;
        double r = nRel == 0 ? 0 : (double) relMostrati / nRel;
        double f1 = (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        double rprec = nRel == 0 ? 0 : (double) contaRilevanti(classifica, rilevanti, nRel) / nRel;
        double p5 = contaRilevanti(classifica, rilevanti, 5) / 5.0;
        double p10 = contaRilevanti(classifica, rilevanti, 10) / 10.0;
        return new Metriche(nRel, classifica.size(), relMostrati, p, r, f1, rprec, p5, p10);
    }

    /** Quanti documenti rilevanti ci sono tra i primi k della classifica. */
    static int contaRilevanti(List<String> classifica, Set<String> rilevanti, int k) {
        int n = 0;
        for (int i = 0; i < Math.min(k, classifica.size()); i++) if (rilevanti.contains(classifica.get(i))) n++;
        return n;
    }

    static double media(List<Metriche> ms, ToDoubleFunction<Metriche> f) {
        return ms.stream().mapToDouble(f).average().orElse(0);
    }

    // ------------------------------------------------------------------ analyzer, indice e ricerca

    /** Analyzer per campo usato per indicizzare, a seconda della configurazione. */
    static Analyzer analyzerIndice(String config) {
        return switch (config) {
            case "standard" -> new StandardAnalyzer();
            default -> new PerFieldAnalyzerWrapper(new StandardAnalyzer(),
                    Map.of(Analizzatori.CAMPO_NOME, analyzerCampo(config, Analizzatori.CAMPO_NOME),
                           Analizzatori.CAMPO_CONTENUTO, analyzerCampo(config, Analizzatori.CAMPO_CONTENUTO)));
        };
    }

    /** Lo stesso analyzer, campo per campo, da usare in fase di ricerca. */
    static Analyzer analyzerCampo(String config, String campo) {
        if (config.equals("standard")) return new StandardAnalyzer();
        if (campo.equals(Analizzatori.CAMPO_NOME)) return Analizzatori.analyzerNome(!config.equals("nome-senza-stemming"));
        return config.equals("contenuto-senza-folding") ? Analizzatori.analyzerContenutoItalianoPuro() : Analizzatori.analyzerContenuto();
    }

    static Directory costruisciIndice(Path docs, Analyzer analyzer) throws IOException {
        Directory dir = new ByteBuffersDirectory();
        try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            for (Path f : Indicizzatore.elencaFileTxt(docs)) w.addDocument(Indicizzatore.creaDocumento(f));
        }
        return dir;
    }

    static Similarity similarita(String nome) {
        return switch (nome) {
            case "TF-IDF" -> new ClassicSimilarity();
            case "LM-Dirichlet" -> new LMDirichletSimilarity();
            default -> new BM25Similarity();
        };
    }

    static Query parse(QueryTest q, Config c) throws ParseException {
        QueryParser parser = new QueryParser(q.campo(), analyzerCampo(c.analyzer(), q.campo()));
        parser.setDefaultOperator(c.operatore());
        return parser.parse(q.testo());
    }

    // ------------------------------------------------------------------ pool per i giudizi

    /** Unisce i primi k risultati di tutte le configurazioni: sono i candidati da giudicare. */
    static void creaPool(List<QueryTest> queries, List<Config> configurazioni, Map<String, Directory> indici, int k) throws Exception {
        Path out = CARTELLA.resolve("pool.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            for (QueryTest q : queries) {
                Set<String> pool = new TreeSet<>();
                for (Config c : configurazioni) {
                    try (IndexReader reader = DirectoryReader.open(indici.get(c.analyzer()))) {
                        IndexSearcher s = new IndexSearcher(reader);
                        s.setSimilarity(similarita(c.ranking()));
                        StoredFields sf = s.storedFields();
                        for (ScoreDoc sd : s.search(parse(q, c), k).scoreDocs) pool.add(sf.document(sd.doc).get(Analizzatori.CAMPO_FILE));
                    }
                }
                pw.printf("# %s  %s %s  --  %s  (%d candidati)%n", q.id(), q.campo(), q.testo(), q.esigenza(), pool.size());
                for (String f : pool) pw.printf("%s 0 %s ?%n", q.id(), f);
            }
        }
        System.out.println("Pool scritto in " + out.toAbsolutePath());
    }

    // ------------------------------------------------------------------ lettura file e stampa

    static List<QueryTest> leggiQuery(Path file) throws IOException {
        List<QueryTest> out = new ArrayList<>();
        for (String riga : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (riga.isBlank() || riga.startsWith("#")) continue;
            String[] p = riga.split("\t");
            out.add(new QueryTest(p[0].strip(), p[1].strip(), p[2].strip(), p.length > 3 ? p[3].strip() : ""));
        }
        return out;
    }

    /** Formato TREC:  <id query> 0 <nome file> <rilevanza 0/1>  (le righe con '#' sono commenti). */
    static Map<String, Set<String>> leggiQrels(Path file) throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String riga : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (riga.isBlank() || riga.startsWith("#")) continue;
            String[] p = riga.strip().split("\\s+");
            if (p.length >= 4 && Integer.parseInt(p[3]) > 0)
                out.computeIfAbsent(p[0], x -> new LinkedHashSet<>()).add(Indicizzatore.nfc(p[2]));
        }
        return out;
    }

    static void stampaTabellaQuery(List<QueryTest> queries, Map<String, Metriche> ris) {
        System.out.printf("    %-4s %-38s %4s %5s %6s %6s %6s %6s %6s %6s%n", "id", "query", "ril", "trov", "P", "R", "F1", "R-Prec", "P@5", "P@10");
        for (QueryTest q : queries) {
            Metriche m = ris.get(q.id());
            String testo = q.campo() + " " + q.testo();
            if (testo.length() > 38) testo = testo.substring(0, 37) + "…";
            System.out.printf("    %-4s %-38s %4d %5d %6.2f %6.2f %6.2f %6.2f %6.2f %6.2f%n", q.id(), testo,
                    m.rilevanti(), m.trovati(), m.p(), m.r(), m.f1(), m.rprec(), m.p5(), m.p10());
        }
    }

    static void scriviDettaglio(PrintWriter pw, QueryTest q, Query query, List<String> classifica, Set<String> rel, Metriche m) {
        pw.printf("=== %s: %s %s%n    esigenza: %s%n    query Lucene: %s%n", q.id(), q.campo(), q.testo(), q.esigenza(), query);
        pw.printf(Locale.ROOT, "    rilevanti=%d trovati=%d rilevanti_nei_primi_%d=%d  P=%.2f R=%.2f F1=%.2f R-Prec=%.2f P@5=%.2f P@10=%.2f%n",
                m.rilevanti(), m.trovati(), K, m.rilMostrati(), m.p(), m.r(), m.f1(), m.rprec(), m.p5(), m.p10());
        for (int i = 0; i < Math.min(K, classifica.size()); i++)
            pw.printf("    %2d. %s %s%n", i + 1, rel.contains(classifica.get(i)) ? "[R]" : "[ ]", classifica.get(i));
        Set<String> mancanti = new HashSet<>(rel);
        mancanti.removeAll(classifica.subList(0, Math.min(K, classifica.size())));
        if (!mancanti.isEmpty()) pw.printf("    rilevanti NON tra i primi %d: %s%n", K, new TreeSet<>(mancanti));
        pw.println();
    }
}
