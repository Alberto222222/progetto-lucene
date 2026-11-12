package it.ingdati.lucene;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Programma 2: legge le query da console, interroga l'indice e stampa i risultati.
 *
 * Uso:  mvn -q compile exec:java@cerca
 *
 * Sintassi della query:   <campo> <termini>
 *   nome batman
 *   nome capitan america
 *   contenuto supereroe mutante              (tutti i termini devono comparire: operatore AND)
 *   contenuto "cavaliere oscuro"            (tra virgolette = phrase query)
 *   contenuto marvel OR dc                   (OR esplicito: basta uno dei due termini)
 *   contenuto marvel -spider                 (- escluso: sintassi del QueryParser)
 * Comandi:  "esci" per terminare.
 */
public class Ricercatore {

    private static final int TOP_K = 10;   // quanti risultati mostrare

    public static void main(String[] args) throws IOException {
        Console.usaUtf8();
        Path cartellaIndice = Paths.get(args.length > 0 ? args[0] : "index");

        try (Directory directory = FSDirectory.open(cartellaIndice);
             IndexReader reader = DirectoryReader.open(directory)) {

            IndexSearcher searcher = new IndexSearcher(reader);   // similarità di default: BM25
            // Un QueryParser per campo, ciascuno con lo STESSO analyzer usato in indicizzazione
            QueryParser parserNome = new QueryParser(Analizzatori.CAMPO_NOME, Analizzatori.analyzerNome());
            QueryParser parserContenuto = new QueryParser(Analizzatori.CAMPO_CONTENUTO, Analizzatori.analyzerContenuto());
            // Operatore di default AND: un documento deve contenere TUTTI i termini (come nei motori di ricerca web).
            // Scelta motivata dalla valutazione: a parità di P@5 aumenta molto la precision (vedi Valutatore).
            parserNome.setDefaultOperator(QueryParser.Operator.AND);
            parserContenuto.setDefaultOperator(QueryParser.Operator.AND);

            System.out.printf("Indice aperto: %d documenti.%n", reader.numDocs());
            System.out.println("Scrivi una query (es.  nome batman   |   contenuto \"cavaliere oscuro\"), oppure 'esci'.");

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                System.out.print("\n> ");
                String riga = console.readLine();
                if (riga == null || riga.isBlank()) {
                    if (riga == null) break;
                    continue;
                }
                riga = Indicizzatore.nfc(riga.strip());
                if (riga.equalsIgnoreCase("esci")) break;

                // 1. Separo la parola chiave del campo dal resto della query
                String[] parti = riga.split("\\s+", 2);
                if (parti.length < 2) {
                    System.out.println("Formato: nome <termini>  oppure  contenuto <termini>");
                    continue;
                }
                String campo = parti[0].toLowerCase();
                String testoQuery = parti[1];

                QueryParser parser = switch (campo) {
                    case "nome" -> parserNome;
                    case "contenuto" -> parserContenuto;
                    default -> null;
                };
                if (parser == null) {
                    System.out.println("Campo sconosciuto: usa 'nome' o 'contenuto'.");
                    continue;
                }

                // 2. Il QueryParser analizza il testo e riconosce da solo le "frasi" tra virgolette
                Query query;
                try {
                    query = parser.parse(testoQuery);
                } catch (ParseException e) {
                    System.out.println("Query non valida: " + e.getMessage());
                    continue;
                }

                // 3. Ricerca + misura del tempo
                long t0 = System.nanoTime();
                TopDocs risultati = searcher.search(query, TOP_K);
                int totale = searcher.count(query);
                double ms = (System.nanoTime() - t0) / 1_000_000.0;

                // 4. Stampa: la query interpretata (utile per capire cosa ha fatto l'analyzer) e i risultati
                System.out.printf("Query Lucene: %s%n", query);
                System.out.printf("%d documenti trovati in %.1f ms%s%n", totale, ms,
                        totale > TOP_K ? " (mostro i primi " + TOP_K + ")" : "");

                StoredFields storedFields = searcher.storedFields();
                int pos = 1;
                for (ScoreDoc sd : risultati.scoreDocs) {
                    Document doc = storedFields.document(sd.doc);
                    System.out.printf("%2d. %-45s score = %.3f%n", pos++, doc.get(Analizzatori.CAMPO_FILE), sd.score);
                }
            }
        }
        System.out.println("Ciao!");
    }
}
