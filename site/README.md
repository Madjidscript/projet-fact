# FACT Immobilier — site public

Le site de l'agence : accueil, biens à louer et à vendre (liste et carte), fiches des biens,
nos services, l'agence, confier mon bien, devis et estimation, contact, favoris, mentions légales.
Les biens, les chiffres clés, les demandes et les alertes passent par l'API publique (`/api/public`).

## Lancer

```
demarrer_site.bat          (à la racine du projet)
```

ou, depuis ce dossier : `npm install` une fois, puis `npm start`. Le site s'ouvre sur
<http://localhost:4320/>. Il lit et envoie tout par l'API : lancer aussi `demarrer.bat` (port 8791).
Après une modification de `angular.json` ou de `proxy.conf.json`, relancer le site.

### Origine des requêtes

L'API refuse les envois (demandes, alertes) venant d'une adresse qu'elle ne connaît pas. En
développement, `proxy.conf.json` présente les requêtes avec l'adresse de l'API. En production,
l'adresse publique du site devra être ajoutée à la liste `FACT_CORS` de l'API (ou le site servi
derrière le même nom de domaine que l'API).

## Logo

Le logo (`src/app/composants/logo.ts`, et le pictogramme `public/marque/favicon.svg`) est une
**reconstitution vectorielle** du fichier reçu de l'agence, `public/marque/logo-fact-original.png`
(154 × 71 px, trop petit pour être agrandi). Elle est **à faire valider par l'agence**, qui devrait
fournir son fichier original en haute définition (vectoriel de préférence) : seuls ces deux
fichiers seront alors à remplacer. Le préchargeur s'appuie sur les classes des éléments du logo
(`immeuble`, `fenetre`, `lettres`, `reflet`, `bandeau`).

La charte suit le logo : bleu roi `#2353A6` (marque), nuit très sombre `#0F1B30` et anthracite
`#2E2E2E` (fonds), argent `#DCE4E4` / `#B4C2D0` (reflets), bleu vif `#1A63D8` pour les appels à
l'action. Le vert, l'orange et le rouge ne servent qu'aux états (réussite, attention, erreur).

## Animations

GSAP 3.12.5 (licence libre) avec ScrollTrigger et Flip — `src/app/noyau/mouvement.ts` :

- préchargeur à la première visite de la session (`composants/prechargeur.ts`, environ 2 s, un clic
  ou une touche l'écourte) : l'immeuble se dresse, ses fenêtres s'allument, un reflet balaie « FACT »,
  le bandeau « IMMOBILIER » glisse, le logo se loge dans l'en-tête et le rideau s'ouvre ;
- titres découpés en mots qui montent derrière un masque, avec un soulignement qui se trace
  (découpage maison : SplitText n'est pas dans la version libre utilisée) ;
- cartes en cascade par lots au défilement, inclinaison 3D et reflet au survol (souris seulement) ;
- liste des biens réordonnée avec Flip quand les filtres changent, nombre de résultats qui roule,
  prix qui s'animent d'une valeur à l'autre ; marqueur de la carte qui rebondit au survol ;
- pictogrammes dessinés pour le site, qui se tracent à leur entrée à l'écran ;
- transitions de page dans le sens de la navigation.

Si l'appareil demande moins d'animations, tout est remplacé par de simples fondus et le
préchargeur ne joue pas.

## Ce qui reste à fournir par l'agence

- Le logo original en haute définition (voir plus haut).
- Photos et noms de l'équipe, numéro d'agrément, mentions légales complètes, témoignages.
- Les comptes d'envoi (e-mail, SMS, WhatsApp) : les accusés de réception et les alertes attendent
  dans l'application de gestion d'être envoyés.
