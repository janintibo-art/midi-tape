# fabkorg 🎛️🩷

Studio MIDI + audio natif Android pour boîtes à rythmes et grooveboxes
(Korg Electribe ER-1, Volca…), en **Kotlin + Jetpack Compose**.
Nom affiché : « Fab La Grosse Basse ».

## 8 pages (balayer, ou onglets)
1. **STUDIO** — transport, pistes avec tête de lecture, ● REC A+M et ▶ PLAY A+M
   (prise et lecture combinées audio+MIDI), SYNC/MASTER, quantize, loop, sessions
2. **LIVE** — 16 pads tactiles façon MPC
3. **PIANO** — piano roll : poser/effacer des notes, vélocité au glisser,
   **copier une mesure vers une autre**, tête de lecture
4. **MIX** — volume/pan en direct, transposition, GROOVE (swing)
5. **AUDIO** — enregistrement WAV **via l'interface USB** (sélecteur de source),
   vumètre, égaliseur, partage et renommage des prises
6. **SET** — mode concert : setlist ordonnée, gros boutons PRÉC./SUIV.
7. **KORG** — télécommande : Start/Stop, saut de pattern, curseurs CC
   assignables, potard NRPN (ER-1)
8. **SONS** — toute la famille Electribe : dumps SysEx (ER-1, ES-1, EMX…)
   et gestion des SAMPLES par carte SD (electribe 2 / sampler) — échange
   de WAV dans les deux sens via le sélecteur de dossier Android

## Connectivité USB
- **Multi-appareils** : plusieurs machines à la fois via un hub USB alimenté,
  chacune avec sa LED d'activité et son bouton de déconnexion
- **Cible d'envoi** : tous les appareils ou un seul
- **Routage THRU** : ce qui arrive d'un appareil part vers les autres,
  avec transposition et canal forcé
- **Télécommande** : un pad USB déclenche REC / PLAY / STOP / OVERDUB
- **Export direct** vers clé USB ou carte SD (sauvegarde totale et prises WAV)

## Création et jeu
- **MIDI Learn** (page KORG) : assigne un curseur en tournant le potard réel
- **Générateur euclidien** + **humaniser** (page PIANO)
- **Song mode** (page SET) : structure A-A-B-A avec répétitions, joué d'affilée
- **Effets créatifs** (page AUDIO) : vitesse/hauteur, à l'envers, filtre, écho

## Import / export
- Import de fichiers **.mid** du téléphone, export .mid quantifié/swingué, partage
- Sessions nommées + **sauvegarde automatique** (« secours-auto »)

## Compilation
GitHub Actions produit à chaque push un **APK release signé**
(**Actions → dernier run → Artifacts → FabLaGrosseBasse-release**),
installable proprement et mis à jour sans désinstaller.
Clé de signature : `release.keystore` (projet personnel — ne pas réutiliser
cette clé pour une app destinée au Play Store).
