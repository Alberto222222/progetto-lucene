# Motore di ricerca Lucene su fumetti, supereroi e animazione

Progetto per il corso di **Ingegneria dei Dati**: un piccolo motore di ricerca basato su
[Apache Lucene](https://lucene.apache.org) 10.6.0 che indicizza file `.txt` e permette di
interrogarli da console sul **nome del file** o sul **contenuto**.

## Struttura

```
progetto-lucene/
├── docs/                    200 file .txt (dataset, voci di Wikipedia in italiano)
├── scarica_dataset.py       script che ha costruito il dataset
├── FONTI.md                 elenco delle voci di Wikipedia usate (licenza CC BY-SA 4.0)
├── pom.xml                  progetto Maven (Java 21, Lucene 10.6.0)
└── src/main/java/it/ingdati/lucene/
    ├── Analizzatori.java    analyzer dei due campi (unico punto di configurazione)
    ├── Indicizzatore.java   programma 1: crea l'indice e misura i tempi
    ├── Ricercatore.java     programma 2: legge le query da console e stampa i risultati
    ├── MostraAnalisi.java   mostra i token prodotti dagli analyzer (utile per la relazione)
    └── Console.java         output della console in UTF-8
```

## Requisiti

- Java 21 o superiore (Lucene 10 richiede Java 21)
- Maven 3.9+

## Come si usa

Dalla cartella del progetto:

```bash
# 1. Indicizza i file di docs/ nella cartella index/
mvn -q compile exec:java@indicizza

# 2. Cerca
mvn -q compile exec:java@cerca

# (facoltativo) Mostra come gli analyzer trasformano alcuni testi d'esempio
mvn -q compile exec:java@analisi
mvn -q compile exec:java@analisi -Dexec.args="contenuto Gli eroi della Marvel"
```

Cartelle diverse si possono passare come argomenti:
`-Dexec.args="cartellaDocumenti cartellaIndice"` (indicizzatore), `-Dexec.args="cartellaIndice"` (ricercatore).

### Sintassi delle query

Ogni query inizia con il campo (`nome` oppure `contenuto`) seguito dai termini:

| Query | Significato |
|---|---|
| `nome batman` | file il cui nome contiene "batman" |
| `nome "spider man"` | phrase query sul nome |
| `contenuto supereroe mutante` | almeno uno dei termini (OR), ordinati per rilevanza |
| `contenuto "cavaliere oscuro"` | phrase query: le parole in sequenza |
| `contenuto +marvel -spider` | `+` obbligatorio, `-` escluso (sintassi del QueryParser di Lucene) |
| `esci` | termina il programma |

Il programma stampa anche la query come l'ha interpretata Lucene (dopo l'analisi),
il numero di documenti trovati, il tempo di risposta e i primi 10 risultati con il punteggio BM25.

## Scelte progettuali

**Documento Lucene** (uno per file):

| Campo | Tipo | Memorizzato | Analyzer |
|---|---|---|---|
| `nome` | `TextField` | sì | analyzer personalizzato per i nomi |
| `contenuto` | `TextField` | no | `ItalianAnalyzer` |
| `file` | `StoredField` | sì | — (solo per stampare il nome completo) |

**Analyzer del campo `nome`**: `CustomAnalyzer` con
`WhitespaceTokenizer → WordDelimiterGraphFilter → FlattenGraphFilter → ASCIIFoldingFilter → LowerCaseFilter`.
I nomi dei file sono identificatori, non frasi: vanno spezzati su `_`, `-`, `.` e sui cambi
maiuscolo/minuscolo (`SpongeBob` → `sponge`, `bob`), resi indipendenti da accenti e maiuscole
(`Pokémon` → `pokemon`), ma senza stopword né stemming. L'estensione `.txt` non viene indicizzata.

**Analyzer del campo `contenuto`**: `ItalianAnalyzer` (StandardTokenizer, elisione `l'`/`dell'`,
minuscolo, stopword italiane, stemming leggero `ItalianLightStemFilter`): i testi sono in italiano,
così `fumetto`/`fumetti` diventano lo stesso termine `fumett`.

Lo **stesso analyzer** è usato in indicizzazione (`PerFieldAnalyzerWrapper`) e in ricerca
(un `QueryParser` per campo). I nomi dei file vengono normalizzati in Unicode NFC, perché macOS
li salva in forma scomposta (NFD) e l'accento separato verrebbe trattato come separatore.

## Dataset

200 voci di Wikipedia in italiano (circa 6,2 MB di testo) su fumetti, supereroi Marvel/DC, autori,
fumetto italiano, manga/anime e animazione, scaricate con `scarica_dataset.py` tramite l'API
MediaWiki (testo semplice, voci sotto i 1500 caratteri e duplicati scartati).
Testi © autori di Wikipedia, licenza [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/);
l'elenco completo con i link è in [FONTI.md](FONTI.md).

## Riferimenti

- Repository di esempi del corso: https://github.com/tteofili/lucenex
- Apache Lucene: https://lucene.apache.org · https://github.com/apache/lucene
