package it.ingdati.lucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Programma 1: indicizza tutti i file .txt di una cartella.
 *
 * Uso:  mvn -q compile exec:java@indicizza
 *       (oppure con argomenti: -Dexec.args="cartellaDocumenti cartellaIndice")
 *
 * Per ogni file crea un Document con due campi indicizzati:
 *   - nome      : il nome del file senza ".txt"   -> analyzerNome()
 *   - contenuto : il testo del file              -> analyzerContenuto()
 * più un campo solo memorizzato "file" con il nome completo da stampare nei risultati.
 */
public class Indicizzatore {

    public static void main(String[] args) throws IOException {
        Console.usaUtf8();
        Path cartellaDocumenti = Paths.get(args.length > 0 ? args[0] : "docs");
        Path cartellaIndice    = Paths.get(args.length > 1 ? args[1] : "index");

        // 1. Elenco dei file .txt da indicizzare (ordinati per avere esecuzioni ripetibili)
        List<Path> files;
        try (Stream<Path> s = Files.list(cartellaDocumenti)) {
            files = s.filter(p -> p.toString().toLowerCase().endsWith(".txt")).sorted().toList();
        }
        System.out.printf("Trovati %d file .txt in %s%n", files.size(), cartellaDocumenti.toAbsolutePath());

        // 2. Configurazione dell'IndexWriter: analyzer per campo + indice ricreato da zero
        IndexWriterConfig config = new IndexWriterConfig(Analizzatori.perCampo());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);   // cancella un eventuale indice precedente

        long totaleByte = 0;
        long inizio = System.nanoTime();

        try (Directory directory = FSDirectory.open(cartellaIndice);
             IndexWriter writer = new IndexWriter(directory, config)) {

            for (Path file : files) {
                // macOS salva i nomi dei file in forma Unicode "scomposta" (NFD): "é" = "e" + accento.
                // Normalizziamo in forma "composta" (NFC), altrimenti l'accento staccato verrebbe visto
                // come separatore e "Pokémon" diventerebbe i due token "poke" e "mon".
                String nomeFile = nfc(file.getFileName().toString());
                String nomeSenzaEstensione = nomeFile.substring(0, nomeFile.length() - ".txt".length());
                String testo = nfc(Files.readString(file, StandardCharsets.UTF_8));
                totaleByte += Files.size(file);

                // 3. Un Document per file
                Document doc = new Document();
                // TextField = testo tokenizzato; Store.YES = possiamo rileggerlo nei risultati
                doc.add(new TextField(Analizzatori.CAMPO_NOME, nomeSenzaEstensione, Field.Store.YES));
                // Store.NO: il contenuto è cercabile ma non lo ricopiamo nell'indice (come l'abstract nelle slide)
                doc.add(new TextField(Analizzatori.CAMPO_CONTENUTO, testo, Field.Store.NO));
                // StoredField: solo memorizzato, non cercabile
                doc.add(new StoredField(Analizzatori.CAMPO_FILE, nomeFile));

                writer.addDocument(doc);
            }

            // 4. Commit: senza commit l'indice resterebbe invisibile in fase di ricerca
            writer.commit();
        }

        long fine = System.nanoTime();
        double ms = (fine - inizio) / 1_000_000.0;

        // 5. Statistiche per la relazione
        System.out.println("--------------------------------------------------");
        System.out.printf("File indicizzati      : %d%n", files.size());
        System.out.printf("Dimensione totale     : %.1f MB%n", totaleByte / (1024.0 * 1024.0));
        System.out.printf("Tempo di indicizzazione: %.0f ms (%.2f s)%n", ms, ms / 1000);
        System.out.printf("Tempo medio per file  : %.2f ms%n", files.isEmpty() ? 0 : ms / files.size());
        System.out.printf("Indice salvato in     : %s%n", cartellaIndice.toAbsolutePath());

        stampaStatistiche(cartellaIndice);
    }

    /** Forma Unicode composta (NFC). */
    static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /**
     * Statistiche dell'indice per ciascun campo (idea presa da testIndexStatistics del repo lucenex):
     * documenti con il campo, numero di termini distinti (dimensione del dizionario),
     * numero totale di occorrenze di termini.
     */
    private static void stampaStatistiche(Path cartellaIndice) throws IOException {
        try (Directory directory = FSDirectory.open(cartellaIndice);
             IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            System.out.println("--------------------------------------------------");
            System.out.printf("%-10s %8s %16s %20s%n", "campo", "doc", "termini distinti", "occorrenze totali");
            for (String campo : new String[] {Analizzatori.CAMPO_NOME, Analizzatori.CAMPO_CONTENUTO}) {
                CollectionStatistics st = searcher.collectionStatistics(campo);
                long distinti = 0;
                Terms terms = MultiTerms.getTerms(reader, campo);
                if (terms != null) {
                    TermsEnum it = terms.iterator();
                    while (it.next() != null) distinti++;
                }
                System.out.printf("%-10s %8d %16d %20d%n", campo,
                        st == null ? 0 : st.docCount(), distinti, st == null ? 0 : st.sumTotalTermFreq());
            }
        }
    }
}
