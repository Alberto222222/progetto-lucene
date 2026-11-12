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
 * Confronta i risultati del sistema con i giudizi di rilevanza (qrels) e calcola, per ogni query:
 *   Precision = rilevanti trovati / documenti trovati
 *   Recall    = rilevanti trovati / rilevanti totali
 *   F1        = 2 * P * R / (P + R)
 *   P@5, P@10 = rilevanti tra i primi 5 / 10 risultati, diviso 5 / 10
 * e la media su tutte le query (macro-media).
 *
 * Oltre al sistema del progetto (analyzer scelti + BM25 + operatore OR) prova altre configurazioni,
 * per giustificare le scelte con i numeri:
 *   - analyzer : "progetto" (CustomAnalyzer sul nome + ItalianAnalyzer con ASCIIFolding sul contenuto)
 *                vs "italiano-puro" (come progetto, ma ItalianAnalyzer di Lucene senza ASCIIFolding sul contenuto)
 *                vs "standard" (StandardAnalyzer su entrambi i campi: nessuna scelta specifica)
 *   - ranking  : BM25 (default di Lucene)  vs  TF-IDF (ClassicSimilarity)  vs  modello linguistico (LMDirichlet)
 *   - operatore: OR (basta un termine)  vs  AND (servono tutti i termini)
 *
 * Uso:  mvn -q compile exec:java@valuta
 *       mvn -q compile exec:java@valuta -Dexec.args="--pool 20"    (genera i candidati da giudicare)
 *
 * File in valutazione/:  query.tsv (input), qrels.txt (input),
 *                        riepilogo.csv, per_query.csv, dettaglio_bm25.txt (output)
 */
public class Valutatore {

    /** Una query della collezione di test. */
    record QueryTest(String id, String campo, String testo, String esigenza) { }

    /** Metriche di una query in una configurazione. */
    record Metriche(int trovati, int rilevanti, int rilTrovati, double p, double r, double f1, double p5, double p10) { }

    /** Una configurazione del sistema da valutare. */
    record Config(String analyzer, String ranking, QueryParser.Operator operatore) {
        String nome() { return analyzer + " / " + ranking + " / " + operatore; }
    }

    static final Path CARTELLA = Paths.get("valutazione");

    public static void main(String[] args) throws Exception {
        Console.usaUtf8();
        Locale.setDefault(Locale.ITALY);
        Path docs = Paths.get("docs");

        List<QueryTest> queries = leggiQuery(CARTELLA.resolve("query.tsv"));
        System.out.printf("Query di test: %d%n", queries.size());

        // Un indice in memoria per ciascuna scelta di analyzer
        Map<String, Directory> indici = new LinkedHashMap<>();
        indici.put("progetto", costruisciIndice(docs, Analizzatori.perCampo()));
        indici.put("italiano-puro", costruisciIndice(docs, new PerFieldAnalyzerWrapper(new StandardAnalyzer(),
                Map.of(Analizzatori.CAMPO_NOME, Analizzatori.analyzerNome(),
                       Analizzatori.CAMPO_CONTENUTO, Analizzatori.analyzerContenutoItalianoPuro()))));
        indici.put("standard", costruisciIndice(docs, new StandardAnalyzer()));

        List<Config> configurazioni = new ArrayList<>();
        for (String a : indici.keySet())
            for (String r : new String[] {"BM25", "TF-IDF", "LM-Dirichlet"})
                for (QueryParser.Operator o : QueryParser.Operator.values())
                    configurazioni.add(new Config(a, r, o));

        if (args.length > 0 && args[0].equals("--pool")) {
            int k = args.length > 1 ? Integer.parseInt(args[1]) : 20;
            creaPool(queries, configurazioni, indici, k);
            return;
        }

        Map<String, Set<String>> qrels = leggiQrels(CARTELLA.resolve("qrels.txt"));
        Config principale = new Config("progetto", "BM25", QueryParser.Operator.OR);

        try (PrintWriter perQuery = new PrintWriter(Files.newBufferedWriter(CARTELLA.resolve("per_query.csv"), StandardCharsets.UTF_8));
             PrintWriter riepilogo = new PrintWriter(Files.newBufferedWriter(CARTELLA.resolve("riepilogo.csv"), StandardCharsets.UTF_8));
             PrintWriter dettaglio = new PrintWriter(Files.newBufferedWriter(CARTELLA.resolve("dettaglio_bm25.txt"), StandardCharsets.UTF_8))) {

            perQuery.println("configurazione;query;campo;testo;rilevanti;trovati;rilevanti_trovati;precision;recall;f1;p@5;p@10");
            riepilogo.println("configurazione;precision;recall;f1;p@5;p@10;tempo_medio_ms");

            System.out.printf("%n%-40s %9s %7s %6s %6s %6s %9s%n", "configurazione", "Precision", "Recall", "F1", "P@5", "P@10", "ms/query");
            for (Config c : configurazioni) {
                Map<String, Metriche> risultati = new LinkedHashMap<>();
                double tempoTotale = 0;
                try (IndexReader reader = DirectoryReader.open(indici.get(c.analyzer()))) {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    searcher.setSimilarity(similarita(c.ranking()));
                    StoredFields sf = searcher.storedFields();
                    boolean stampaDettaglio = c.equals(principale);

                    for (QueryTest q : queries) {
                        Query query = parse(q, c);
                        // tempo di risposta: media di 5 esecuzioni dopo una di "riscaldamento"
                        searcher.search(query, reader.maxDoc());
                        long t0 = System.nanoTime();
                        ScoreDoc[] hits = null;
                        for (int i = 0; i < 5; i++) hits = searcher.search(query, reader.maxDoc()).scoreDocs;
                        tempoTotale += (System.nanoTime() - t0) / 5 / 1_000_000.0;

                        List<String> classifica = new ArrayList<>();
                        for (ScoreDoc sd : hits) classifica.add(sf.document(sd.doc).get(Analizzatori.CAMPO_FILE));

                        Set<String> rel = qrels.getOrDefault(q.id(), Set.of());
                        Metriche m = calcola(classifica, rel);
                        risultati.put(q.id(), m);
                        perQuery.printf(Locale.ROOT, "%s;%s;%s;%s;%d;%d;%d;%.4f;%.4f;%.4f;%.4f;%.4f%n", c.nome(), q.id(), q.campo(),
                                q.testo().replace(";", ","), m.rilevanti(), m.trovati(), m.rilTrovati(), m.p(), m.r(), m.f1(), m.p5(), m.p10());
                        if (stampaDettaglio) scriviDettaglio(dettaglio, q, query, classifica, rel, m);
                    }
                }
                double n = queries.size();
                double mp = risultati.values().stream().mapToDouble(Metriche::p).sum() / n;
                double mr = risultati.values().stream().mapToDouble(Metriche::r).sum() / n;
                double mf = risultati.values().stream().mapToDouble(Metriche::f1).sum() / n;
                double m5 = risultati.values().stream().mapToDouble(Metriche::p5).sum() / n;
                double m10 = risultati.values().stream().mapToDouble(Metriche::p10).sum() / n;
                double ms = tempoTotale / n;
                System.out.printf("%-40s %9.3f %7.3f %6.3f %6.3f %6.3f %9.2f%s%n", c.nome(), mp, mr, mf, m5, m10, ms,
                        c.equals(principale) ? "   <- sistema del progetto" : "");
                riepilogo.printf(Locale.ROOT, "%s;%.4f;%.4f;%.4f;%.4f;%.4f;%.3f%n", c.nome(), mp, mr, mf, m5, m10, ms);

                if (c.equals(principale)) stampaTabellaQuery(queries, risultati);
            }
        }
        System.out.println("\nFile scritti in " + CARTELLA.toAbsolutePath() + ": riepilogo.csv, per_query.csv, dettaglio_bm25.txt");
    }

    // ------------------------------------------------------------------ metriche

    /** Calcola le metriche confrontando la classifica restituita con l'insieme dei rilevanti. */
    static Metriche calcola(List<String> classifica, Set<String> rilevanti) {
        int trovati = classifica.size();
        int rilTrovati = 0, ril5 = 0, ril10 = 0;
        for (int i = 0; i < classifica.size(); i++) {
            if (rilevanti.contains(classifica.get(i))) {
                rilTrovati++;
                if (i < 5) ril5++;
                if (i < 10) ril10++;
            }
        }
        double p = trovati == 0 ? 0 : (double) rilTrovati / trovati;
        double r = rilevanti.isEmpty() ? 0 : (double) rilTrovati / rilevanti.size();
        double f1 = (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        return new Metriche(trovati, rilevanti.size(), rilTrovati, p, r, f1, ril5 / 5.0, ril10 / 10.0);
    }

    // ------------------------------------------------------------------ indice e ricerca

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
        Analyzer a;
        if (c.analyzer().equals("progetto")) {
            a = q.campo().equals(Analizzatori.CAMPO_NOME) ? Analizzatori.analyzerNome() : Analizzatori.analyzerContenuto();
        } else if (c.analyzer().equals("italiano-puro")) {
            a = q.campo().equals(Analizzatori.CAMPO_NOME) ? Analizzatori.analyzerNome() : Analizzatori.analyzerContenutoItalianoPuro();
        } else {
            a = new StandardAnalyzer();
        }
        QueryParser parser = new QueryParser(q.campo(), a);
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
        System.out.printf("%n    %-4s %-38s %4s %5s %6s %6s %6s %6s %6s%n", "id", "query", "ril", "trov", "P", "R", "F1", "P@5", "P@10");
        for (QueryTest q : queries) {
            Metriche m = ris.get(q.id());
            String testo = q.campo() + " " + q.testo();
            if (testo.length() > 38) testo = testo.substring(0, 37) + "…";
            System.out.printf("    %-4s %-38s %4d %5d %6.2f %6.2f %6.2f %6.2f %6.2f%n", q.id(), testo,
                    m.rilevanti(), m.trovati(), m.p(), m.r(), m.f1(), m.p5(), m.p10());
        }
        System.out.println();
    }

    static void scriviDettaglio(PrintWriter pw, QueryTest q, Query query, List<String> classifica, Set<String> rel, Metriche m) {
        pw.printf("=== %s: %s %s%n    esigenza: %s%n    query Lucene: %s%n", q.id(), q.campo(), q.testo(), q.esigenza(), query);
        pw.printf(Locale.ROOT, "    rilevanti=%d trovati=%d rilevanti_trovati=%d  P=%.2f R=%.2f F1=%.2f P@5=%.2f P@10=%.2f%n",
                m.rilevanti(), m.trovati(), m.rilTrovati(), m.p(), m.r(), m.f1(), m.p5(), m.p10());
        for (int i = 0; i < Math.min(10, classifica.size()); i++)
            pw.printf("    %2d. %s %s%n", i + 1, rel.contains(classifica.get(i)) ? "[R]" : "[ ]", classifica.get(i));
        Set<String> mancanti = new HashSet<>(rel);
        mancanti.removeAll(classifica);
        if (!mancanti.isEmpty()) pw.printf("    rilevanti NON trovati: %s%n", new TreeSet<>(mancanti));
        pw.println();
    }
}
