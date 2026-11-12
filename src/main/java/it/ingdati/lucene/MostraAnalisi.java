package it.ingdati.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

/**
 * Programma di supporto: mostra quali termini producono i due analyzer su alcuni esempi.
 * Serve a capire (e documentare nella relazione) l'effetto delle scelte di analisi.
 * Idea presa da testAnalyzer del repository del corso (tteofili/lucenex).
 *
 * Uso:  mvn -q compile exec:java@analisi
 *       mvn -q compile exec:java@analisi -Dexec.args="contenuto Gli eroi della Marvel"
 */
public class MostraAnalisi {

    public static void main(String[] args) throws IOException {
        Console.usaUtf8();
        if (args.length >= 2) {
            String campo = args[0];
            String testo = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            Analyzer a = campo.equals(Analizzatori.CAMPO_NOME) ? Analizzatori.analyzerNome() : Analizzatori.analyzerContenuto();
            stampa(campo, a, testo);
            return;
        }
        Analyzer nome = Analizzatori.analyzerNome();
        Analyzer contenuto = Analizzatori.analyzerContenuto();

        System.out.println("=== Campo NOME (CustomAnalyzer) ===");
        for (String s : new String[] {"Pokémon_serie_animata", "Spider-Man_-_Un_nuovo_universo",
                                      "Thor_Marvel_Comics", "L_età_dell_oro_fumetto", "Ms._Marvel", "SpongeBob"}) {
            stampa("nome", nome, s);
        }
        System.out.println("\n=== Campo CONTENUTO (ItalianAnalyzer) ===");
        for (String s : new String[] {"Gli eroi della Marvel combattono i supercriminali.",
                                      "L'Uomo Ragno è un supereroe dei fumetti.",
                                      "Il Cavaliere Oscuro difende Gotham City.",
                                      "fumetto fumetti fumettista fumettistico",
                                      "Dragon Ball è un manga shōnen di Akira Toriyama."}) {
            stampa("contenuto", contenuto, s);
        }
    }

    /** Stampa i token prodotti; un "_" segnala una posizione vuota (es. stopword rimossa). */
    static void stampa(String campo, Analyzer analyzer, String testo) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(campo, testo)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            PositionIncrementAttribute inc = ts.addAttribute(PositionIncrementAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                for (int i = 1; i < inc.getPositionIncrement(); i++) tokens.add("_");
                tokens.add(term.toString());
            }
            ts.end();
        }
        System.out.printf("%-50s -> %s%n", "\"" + testo + "\"", tokens);
    }
}
