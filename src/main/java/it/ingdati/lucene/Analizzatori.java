package it.ingdati.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.it.ItalianLightStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.util.ElisionFilter;
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

    /** Articoli elisi dell'italiano (stessa lista usata internamente da ItalianAnalyzer). */
    private static final CharArraySet ARTICOLI = new CharArraySet(Arrays.asList(
            "c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell", "gl", "agl", "dagl",
            "degl", "negl", "sugl", "un", "m", "t", "s", "v", "d"), true);

    /**
     * Analyzer per il CONTENUTO: testo in italiano (voci di Wikipedia).
     *
     * È la stessa catena di ItalianAnalyzer con in più l'ASCIIFolding:
     *   StandardTokenizer         : parole secondo le regole Unicode
     *   ElisionFilter             : l'eroe -> eroe, dell'universo -> universo
     *   LowerCaseFilter           : minuscolo
     *   StopFilter (italiano)     : toglie il, di, che, della, ...
     *   ASCIIFoldingFilter        : shōnen -> shonen, Mystère -> mystere (l'utente scrive senza segni diacritici;
     *                               aggiunto dopo la valutazione: senza, la query "shonen" non trovava "shōnen")
     *   ItalianLightStemFilter    : fumetto / fumetti -> fumett
     */
    public static Analyzer analyzerContenuto() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String campo) {
                Tokenizer source = new StandardTokenizer();
                TokenStream ts = new ElisionFilter(source, ARTICOLI);
                ts = new LowerCaseFilter(ts);
                ts = new StopFilter(ts, ItalianAnalyzer.getDefaultStopSet());
                ts = new ASCIIFoldingFilter(ts);
                ts = new ItalianLightStemFilter(ts);
                return new TokenStreamComponents(source, ts);
            }

            @Override
            protected TokenStream normalize(String campo, TokenStream in) {
                return new ASCIIFoldingFilter(new LowerCaseFilter(new ElisionFilter(in, ARTICOLI)));
            }
        };
    }

    /** ItalianAnalyzer "puro" di Lucene (senza ASCIIFolding): usato solo nel confronto del Valutatore. */
    public static Analyzer analyzerContenutoItalianoPuro() {
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
