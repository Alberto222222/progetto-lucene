package it.ingdati.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.FlattenGraphFilterFactory;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.core.WhitespaceTokenizerFactory;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilterFactory;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * Raccoglie in un unico punto le scelte di analisi del testo.
 *
 * Indicizzatore e Ricercatore usano ENTRAMBI questa classe: così siamo sicuri
 * che i termini prodotti in fase di ricerca siano identici a quelli indicizzati
 * (regola d'oro delle slide: "use consistent analyzers both for indexing and searching").
 */
public final class Analizzatori {

    /** Nomi dei campi, usati ovunque nel progetto. */
    public static final String CAMPO_NOME = "nome";
    public static final String CAMPO_CONTENUTO = "contenuto";
    public static final String CAMPO_FILE = "file";   // nome completo del file, solo memorizzato

    private Analizzatori() { }

    /**
     * Analyzer per il NOME del file (es. "Capitan_America", "Pokémon_serie_animata").
     *
     * Un nome di file non è una frase in linguaggio naturale ma un identificatore:
     *  - whitespace          : primo taglio sugli spazi (nei nostri nomi di solito non ce ne sono)
     *  - wordDelimiterGraph  : spezza su '_', '-', '.', cambi maiuscolo/minuscolo (IronMan -> Iron, Man)
     *                          e passaggi lettere/numeri (Film2024 -> Film, 2024)
     *  - flattenGraph        : richiesto a indicizzazione dopo un filtro "Graph"
     *  - asciiFolding        : toglie gli accenti (Pokémon -> Pokemon, Eiichirō -> Eiichiro)
     *  - lowercase           : maiuscole e minuscole non contano
     * Niente stopword né stemming: in un nome ogni parola conta ed è già "in forma base".
     */
    public static Analyzer analyzerNome() {
        try {
            return CustomAnalyzer.builder()
                    .withTokenizer(WhitespaceTokenizerFactory.class)
                    .addTokenFilter(WordDelimiterGraphFilterFactory.class)
                    .addTokenFilter(FlattenGraphFilterFactory.class)
                    .addTokenFilter(ASCIIFoldingFilterFactory.class)
                    .addTokenFilter(LowerCaseFilterFactory.class)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Analyzer per il CONTENUTO: testo in italiano (voci di Wikipedia).
     *
     * ItalianAnalyzer = StandardTokenizer + ElisionFilter (l'eroe -> eroe)
     *                   + LowerCaseFilter + StopFilter (il, di, che, ...)
     *                   + ItalianLightStemFilter (fumetti / fumetto -> stessa radice)
     */
    public static Analyzer analyzerContenuto() {
        return new ItalianAnalyzer();
    }

    /**
     * Un unico Analyzer che sceglie quello giusto in base al campo.
     * Il primo argomento (StandardAnalyzer) vale per i campi non elencati nella mappa.
     */
    public static Analyzer perCampo() {
        return new PerFieldAnalyzerWrapper(
                new StandardAnalyzer(),
                Map.of(CAMPO_NOME, analyzerNome(),
                       CAMPO_CONTENUTO, analyzerContenuto()));
    }
}
