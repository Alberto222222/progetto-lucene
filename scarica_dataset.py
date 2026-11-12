#!/usr/bin/env python3
"""
scarica_dataset.py
------------------
Scarica da Wikipedia in italiano le voci su fumetti, supereroi e animazione
e le salva come file .txt nella cartella  docs/  (accanto a questo script).

Sono i documenti che il nostro motore di ricerca Lucene indicizzerà.

Uso (dal Terminale, dentro la cartella del progetto):
    python3 scarica_dataset.py

Se si interrompe (o Wikipedia rallenta) basta rilanciarlo: riprende da dove
era arrivato, perché ricorda in  progresso.json  cosa ha già scaricato.

Usa solo la libreria standard di Python: non serve installare nulla.
I testi di Wikipedia sono distribuiti con licenza CC BY-SA 4.0
(fonte: https://it.wikipedia.org) – lo script crea anche docs/../FONTI.md.
"""

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://it.wikipedia.org/w/api.php"
HEADERS = {"User-Agent": "ProgettoLucene-IngegneriaDeiDati/1.0 (progetto universitario)"}
MIN_CARATTERI = 1500          # scarta voci troppo corte (stub)
PAUSA = 1.5                   # secondi di attesa tra una richiesta e l'altra
MAX_TENTATIVI = 6             # tentativi dopo un errore 429 prima di arrendersi

# Argomenti da cercare: per ognuno si prende il primo risultato della ricerca di Wikipedia.
ARGOMENTI = [
    # --- Concetti generali ---
    "Fumetto", "Supereroe", "Supercriminale", "Graphic novel", "Manga", "Anime",
    "Fumetto italiano", "Fumetto franco-belga", "Animazione", "Cartone animato",
    "Comic book", "Età dell'oro dei fumetti", "Età d'argento dei fumetti",
    "Marvel Comics", "DC Comics", "Universo Marvel", "Universo DC",
    "Marvel Cinematic Universe", "DC Extended Universe", "Image Comics",
    "Sergio Bonelli Editore", "Shōnen", "Shōjo", "Webcomic",
    # --- Supereroi Marvel ---
    "Spider-Man", "Iron Man", "Capitan America", "Thor (Marvel Comics)", "Hulk",
    "Vedova Nera (Natasha Romanoff)", "Occhio di Falco", "Black Panther (personaggio)",
    "Doctor Strange", "Wolverine", "X-Men", "Fantastici Quattro", "Vendicatori",
    "Deadpool", "Daredevil", "Capitan Marvel", "Silver Surfer", "Ant-Man",
    "Guardiani della Galassia", "Punisher", "Ghost Rider", "Scarlet Witch",
    # --- Supereroi DC ---
    "Superman", "Batman", "Wonder Woman", "Flash (fumetto)", "Lanterna Verde",
    "Aquaman", "Justice League", "Robin (personaggio)", "Catwoman", "Green Arrow",
    "Shazam (personaggio)", "Cyborg (DC Comics)", "Teen Titans",
    # --- Cattivi ---
    "Joker (personaggio)", "Lex Luthor", "Magneto (personaggio)", "Thanos",
    "Dottor Destino", "Loki (Marvel Comics)", "Venom (personaggio)", "Harley Quinn",
    "Goblin (personaggio)", "Darkseid",
    # --- Autori ---
    "Stan Lee", "Jack Kirby", "Steve Ditko", "Alan Moore", "Frank Miller",
    "Neil Gaiman", "Osamu Tezuka", "Hayao Miyazaki", "Akira Toriyama",
    "Eiichirō Oda", "Hugo Pratt", "Sergio Bonelli", "Milo Manara", "Zerocalcare",
    "Walt Disney", "Carl Barks",
    # --- Opere a fumetti ---
    "Watchmen", "Il ritorno del Cavaliere Oscuro", "Maus", "Sandman (DC Comics)",
    "V for Vendetta", "Persepolis (fumetto)", "Peanuts", "Asterix", "Le avventure di Tintin",
    "Topolino (periodico)", "Tex (fumetto)", "Dylan Dog", "Diabolik", "Corto Maltese",
    "Martin Mystère", "Lupo Alberto", "Paperinik",
    # --- Manga e anime ---
    "Dragon Ball", "One Piece", "Naruto", "Akira (manga)", "Astro Boy",
    "Ken il guerriero", "I Cavalieri dello zodiaco", "Sailor Moon", "Neon Genesis Evangelion",
    "L'attacco dei giganti", "Death Note", "Detective Conan", "Pokémon (anime)",
    "Lupin III", "Goldrake", "Doraemon", "Fullmetal Alchemist", "Demon Slayer",
    # --- Animazione occidentale e studi ---
    "Walt Disney Animation Studios", "Pixar", "DreamWorks Animation", "Studio Ghibli",
    "Warner Bros. Animation", "Topolino", "Paperino", "Looney Tunes", "Bugs Bunny",
    "I Simpson", "Futurama", "I Griffin", "South Park", "SpongeBob", "Scooby-Doo",
    "Tom & Jerry", "Biancaneve e i sette nani (film 1937)", "Il re leone",
    "Toy Story", "La città incantata", "Il mio vicino Totoro",
    "Spider-Man - Un nuovo universo", "Shrek",
    # --- Altri personaggi Marvel ---
    "Ciclope (personaggio)", "Tempesta (personaggio)", "Jean Grey", "Professor X",
    "Bestia (personaggio)", "Gambit (personaggio)", "Rogue (personaggio)", "Nick Fury",
    "Visione (personaggio)", "Falcon (personaggio)", "Soldato d'Inverno", "Star-Lord",
    "Groot", "Rocket Raccoon", "Moon Knight", "Luke Cage", "Jessica Jones", "Iron Fist",
    "She-Hulk", "Ms. Marvel", "Blade (personaggio)", "Namor", "Galactus", "Ultron",
    "Teschio Rosso", "Kingpin (personaggio)", "Mystica", "Carnage (personaggio)",
    # --- Altri personaggi DC ---
    "Supergirl", "Batgirl", "Martian Manhunter", "Nightwing", "Swamp Thing", "Zatanna",
    "Bane (personaggio)", "Enigmista (personaggio)", "Due Facce", "Pinguino (personaggio)",
    "Spaventapasseri (DC Comics)", "Ra's al Ghul", "Brainiac (personaggio)", "Sinestro",
    "Gotham City", "Kryptonite",
    # --- Altri fumetti ---
    "Hellboy", "Spawn (fumetto)", "The Boys (fumetto)", "Invincible (fumetto)",
    "Tartarughe Ninja", "Kick-Ass (fumetto)", "Sin City (fumetto)", "Hellblazer",
    "The Walking Dead (fumetto)", "Saga (fumetto)", "Usagi Yojimbo",
    "Zagor", "Nathan Never", "Mister No", "Julia (fumetto)", "Kriminal", "Satanik",
    "Valentina (fumetto)", "Corriere dei Piccoli", "Rat-Man", "Cattivik",
    "Andrea Pazienza", "Guido Crepax", "Benito Jacovitti", "Romano Scarpa",
    "Giorgio Cavazzano", "Lucca Comics & Games", "Gian Luigi Bonelli",
    # --- Altri manga e anime ---
    "Jujutsu Kaisen", "My Hero Academia", "Bleach (manga)", "Hunter × Hunter",
    "Le bizzarre avventure di JoJo", "Slam Dunk (manga)", "Capitan Tsubasa", "Lamù",
    "Ranma ½", "Mazinga Z", "Gundam", "Cowboy Bebop", "Ghost in the Shell", "Berserk",
    "Chainsaw Man", "Tokyo Ghoul", "One-Punch Man", "Haikyū!!", "Lady Oscar",
    "Candy Candy", "Toei Animation", "Kyoto Animation", "Makoto Shinkai",
    "Your Name.", "Rumiko Takahashi", "Gō Nagai", "Leiji Matsumoto",
    "Masashi Kishimoto", "Naoko Takeuchi",
    # --- Altri film e serie animate ---
    "Frozen - Il regno di ghiaccio", "La sirenetta (film 1989)",
    "La bella e la bestia (film 1991)", "Aladdin (film 1992)", "Fantasia (film 1940)",
    "Alla ricerca di Nemo", "Gli Incredibili - Una normale famiglia di supereroi",
    "Up (film 2009)", "WALL•E", "Inside Out", "Coco (film 2017)", "Ratatouille (film)",
    "Cars - Motori ruggenti", "Kung Fu Panda", "Madagascar (film)", "Cattivissimo me",
    "Minions (film)", "Illumination", "L'era glaciale", "Aardman Animations",
    "Wallace & Gromit", "Il castello errante di Howl", "Principessa Mononoke",
    "Nausicaä della Valle del vento", "Hanna-Barbera", "Gli antenati", "Braccio di Ferro",
    "Betty Boop", "La Pantera Rosa", "Avatar - La leggenda di Aang", "Adventure Time",
    "Rick and Morty", "Gravity Falls", "BoJack Horseman", "Arcane (serie animata)",
    "Batman (serie animata)", "Winx Club", "Animazione a passo uno",
    "Computer grafica 3D", "Rotoscopio", "Oscar al miglior film d'animazione",
]

OBIETTIVO = 200               # si ferma quando ha salvato 200 file


class TroppeRichieste(Exception):
    pass


def api_get(params):
    """Chiama l'API di Wikipedia. Se riceve 429 (troppe richieste) aspetta e ritenta."""
    params = {**params, "format": "json", "formatversion": "2", "maxlag": "5"}
    url = API + "?" + urllib.parse.urlencode(params)
    attesa = 10
    for tentativo in range(1, MAX_TENTATIVI + 1):
        time.sleep(PAUSA)
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code not in (429, 503):
                raise
            # Wikipedia può dire quanto aspettare nell'header Retry-After
            ra = e.headers.get("Retry-After")
            pausa = int(ra) if ra and ra.isdigit() else attesa
            print(f"      ...Wikipedia chiede di rallentare (errore {e.code}), aspetto {pausa}s "
                  f"[tentativo {tentativo}/{MAX_TENTATIVI}]")
            time.sleep(pausa)
            attesa = min(attesa * 2, 120)
    raise TroppeRichieste("troppe richieste anche dopo vari tentativi")


def scarica_voce(argomento):
    """Restituisce (titolo, testo) della voce. Prima prova il titolo esatto
    (1 sola richiesta); se la voce non esiste, usa la ricerca di Wikipedia."""
    data = api_get({"action": "query", "prop": "extracts", "explaintext": 1,
                    "redirects": 1, "titles": argomento})
    pagine = data.get("query", {}).get("pages", [])
    if pagine and not pagine[0].get("missing") and pagine[0].get("extract"):
        return pagine[0]["title"], pagine[0]["extract"]
    # titolo non trovato: cerco la voce più pertinente
    data = api_get({"action": "query", "generator": "search", "gsrsearch": argomento,
                    "gsrlimit": 1, "prop": "extracts", "explaintext": 1})
    pagine = data.get("query", {}).get("pages", [])
    if pagine:
        return pagine[0]["title"], pagine[0].get("extract", "")
    return None, ""


def nome_file(titolo):
    """'Thor (Marvel Comics)' -> 'Thor_Marvel_Comics.txt'"""
    s = re.sub(r"[()\"'’,:!?/\\]", " ", titolo)
    s = re.sub(r"\s+", "_", s.strip())
    return s + ".txt"


def pulisci(testo):
    """Toglie le intestazioni di sezione '== ... ==' e le righe vuote in eccesso."""
    testo = re.sub(r"^=+\s*(.*?)\s*=+\s*$", r"\1", testo, flags=re.MULTILINE)
    return re.sub(r"\n{3,}", "\n\n", testo).strip()


def main():
    base = Path(__file__).resolve().parent
    docs = base / "docs"
    docs.mkdir(exist_ok=True)
    file_progresso = base / "progresso.json"

    # progresso: argomento -> {"titolo", "file"} oppure {"saltato": motivo}
    progresso = json.loads(file_progresso.read_text(encoding="utf-8")) if file_progresso.exists() else {}
    salvati = lambda: sum(1 for v in progresso.values() if v.get("file"))
    # file già presenti da un'esecuzione precedente (es. la prima versione dello script)
    for a in ARGOMENTI:
        if a not in progresso and (docs / nome_file(a)).exists():
            progresso[a] = {"titolo": a, "file": nome_file(a)}
    titoli_visti = {v["titolo"] for v in progresso.values() if v.get("titolo")}

    print(f"File già scaricati: {salvati()}  –  obiettivo: {OBIETTIVO}\n")
    for i, argomento in enumerate(ARGOMENTI, 1):
        if salvati() >= OBIETTIVO:
            break
        if argomento in progresso:
            continue                      # già fatto in un'esecuzione precedente
        try:
            titolo, testo = scarica_voce(argomento)
        except TroppeRichieste:
            print("\n!! Wikipedia continua a rifiutare le richieste. Aspetta 10-15 minuti e "
                  "rilancia lo script: ripartirà da qui.")
            break
        except Exception as e:
            print(f"[{i:3}] !! errore su '{argomento}': {e}")
            continue

        testo = pulisci(testo)
        if not titolo or titolo in titoli_visti:
            progresso[argomento] = {"saltato": "duplicato o non trovato"}
            print(f"[{i:3}] -- saltato (duplicato o non trovato): {argomento}")
        elif len(testo) < MIN_CARATTERI:
            progresso[argomento] = {"saltato": "troppo corto", "titolo": titolo}
            print(f"[{i:3}] -- saltato (troppo corto): {titolo}")
        else:
            file = docs / nome_file(titolo)
            file.write_text(testo, encoding="utf-8")
            progresso[argomento] = {"titolo": titolo, "file": file.name}
            print(f"[{i:3}] OK  {file.name:45} {len(testo):>7} caratteri   ({salvati()}/{OBIETTIVO})")
        titoli_visti.add(titolo)
        file_progresso.write_text(json.dumps(progresso, ensure_ascii=False, indent=1), encoding="utf-8")

    # FONTI.md con tutte le voci scaricate (anche nelle esecuzioni precedenti)
    righe = []
    for v in progresso.values():
        if v.get("file"):
            url = "https://it.wikipedia.org/wiki/" + urllib.parse.quote(v["titolo"].replace(" ", "_"))
            righe.append(f"- {v['file']} ← [{v['titolo']}]({url})")
    (base / "FONTI.md").write_text(
        "# Fonti del dataset\n\nTesti tratti da Wikipedia in italiano, licenza CC BY-SA 4.0.\n\n"
        + "\n".join(sorted(righe)) + "\n", encoding="utf-8")

    n = len(list(docs.glob("*.txt")))
    totale = sum(f.stat().st_size for f in docs.glob("*.txt"))
    print(f"\nFatto: {n} file .txt in {docs}  ({totale/1024:.0f} KB in totale)")
    if n < OBIETTIVO:
        print(f"Non sono ancora {OBIETTIVO}: rilancia lo script (magari tra qualche minuto) per continuare.")


if __name__ == "__main__":
    main()
