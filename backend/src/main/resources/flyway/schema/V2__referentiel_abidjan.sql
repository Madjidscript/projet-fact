-- =============================================================================
-- FACT Immobilier — Référentiel géographique : communes et quartiers d'Abidjan
-- -----------------------------------------------------------------------------
-- Les points « centre » sont des repères de centrage approximatifs, suffisants
-- pour cadrer la carte et rattacher un bâtiment à sa commune. Les limites
-- officielles (colonne geom) restent nulles : elles seront chargées depuis un
-- GeoJSON du District autonome d'Abidjan (voir database/README.md).
-- Liste des quartiers à compléter avec l'agence au cadrage (§ 7.2).
-- =============================================================================

INSERT INTO adm.commune (id_commune, code, libelle, district, ordre, centre) VALUES
    (1,  'ABOBO',        'Abobo',        'Abidjan', 1,  ST_SetSRID(ST_MakePoint(-4.0159, 5.4319), 4326)),
    (2,  'ADJAME',       'Adjamé',       'Abidjan', 2,  ST_SetSRID(ST_MakePoint(-4.0239, 5.3647), 4326)),
    (3,  'ATTECOUBE',    'Attécoubé',    'Abidjan', 3,  ST_SetSRID(ST_MakePoint(-4.0497, 5.3419), 4326)),
    (4,  'COCODY',       'Cocody',       'Abidjan', 4,  ST_SetSRID(ST_MakePoint(-3.9869, 5.3516), 4326)),
    (5,  'KOUMASSI',     'Koumassi',     'Abidjan', 5,  ST_SetSRID(ST_MakePoint(-3.9519, 5.2958), 4326)),
    (6,  'MARCORY',      'Marcory',      'Abidjan', 6,  ST_SetSRID(ST_MakePoint(-3.9869, 5.3031), 4326)),
    (7,  'PLATEAU',      'Le Plateau',   'Abidjan', 7,  ST_SetSRID(ST_MakePoint(-4.0219, 5.3247), 4326)),
    (8,  'PORT_BOUET',   'Port-Bouët',   'Abidjan', 8,  ST_SetSRID(ST_MakePoint(-3.9281, 5.2583), 4326)),
    (9,  'TREICHVILLE',  'Treichville',  'Abidjan', 9,  ST_SetSRID(ST_MakePoint(-4.0100, 5.2936), 4326)),
    (10, 'YOPOUGON',     'Yopougon',     'Abidjan', 10, ST_SetSRID(ST_MakePoint(-4.0825, 5.3389), 4326)),
    (11, 'ANYAMA',       'Anyama',       'Abidjan', 11, ST_SetSRID(ST_MakePoint(-4.0517, 5.4947), 4326)),
    (12, 'BINGERVILLE',  'Bingerville',  'Abidjan', 12, ST_SetSRID(ST_MakePoint(-3.8853, 5.3550), 4326)),
    (13, 'SONGON',       'Songon',       'Abidjan', 13, ST_SetSRID(ST_MakePoint(-4.2500, 5.3167), 4326)),
    -- Environs où l'agence peut gérer ou vendre des biens
    (20, 'GRAND_BASSAM', 'Grand-Bassam', 'Sud-Comoé',   20, ST_SetSRID(ST_MakePoint(-3.7381, 5.2047), 4326)),
    (21, 'DABOU',        'Dabou',        'Grands-Ponts', 21, ST_SetSRID(ST_MakePoint(-4.3772, 5.3256), 4326));

-- -----------------------------------------------------------------------------
-- Quartiers : Cocody d'abord (siège de l'agence et cœur du portefeuille),
-- puis les principaux quartiers des autres communes.
-- -----------------------------------------------------------------------------
INSERT INTO adm.quartier (id_commune, code, libelle, ordre) VALUES
    -- Cocody
    (4, 'COC_ANGRE',            'Angré', 1),
    (4, 'COC_ANGRE_7E',         'Angré 7e tranche', 2),
    (4, 'COC_ANGRE_8E',         'Angré 8e tranche', 3),
    (4, 'COC_ANGRE_9E',         'Angré 9e tranche', 4),
    (4, 'COC_RIVIERA_1',        'Riviera 1', 5),
    (4, 'COC_RIVIERA_2',        'Riviera 2', 6),
    (4, 'COC_RIVIERA_3',        'Riviera 3', 7),
    (4, 'COC_RIVIERA_4',        'Riviera 4', 8),
    (4, 'COC_RIVIERA_PALM',     'Riviera Palmeraie', 9),
    (4, 'COC_RIVIERA_BONOUMIN', 'Riviera Bonoumin', 10),
    (4, 'COC_CITE_SIR',         'Cité Sir', 11),
    (4, 'COC_DEUX_PLATEAUX',    'Deux Plateaux', 12),
    (4, 'COC_VALLONS',          'Deux Plateaux Vallons', 13),
    (4, 'COC_AGHIEN',           'Aghien', 14),
    (4, 'COC_MBADON',           'M''Badon', 15),
    (4, 'COC_BLOCKHAUSS',       'Blockhauss', 16),
    (4, 'COC_DANGA',            'Danga', 17),
    (4, 'COC_CENTRE',           'Cocody Centre', 18),
    (4, 'COC_AMBASSADES',       'Cocody Ambassades', 19),
    (4, 'COC_SAINT_JEAN',       'Saint-Jean', 20),
    (4, 'COC_ABATTA',           'Abatta', 21),
    (4, 'COC_AKOUEDO',          'Akouédo', 22),
    (4, 'COC_FAYA',             'Faya', 23),

    -- Yopougon
    (10, 'YOP_NIANGON_NORD',  'Niangon Nord', 1),
    (10, 'YOP_NIANGON_SUD',   'Niangon Sud', 2),
    (10, 'YOP_SIPOREX',       'Siporex', 3),
    (10, 'YOP_SELMER',        'Selmer', 4),
    (10, 'YOP_SIDECI',        'Sideci', 5),
    (10, 'YOP_SOGEFIHA',      'Sogefiha', 6),
    (10, 'YOP_TOITS_ROUGES',  'Toits Rouges', 7),
    (10, 'YOP_ANDOKOI',       'Andokoi', 8),
    (10, 'YOP_BANCO',         'Banco', 9),
    (10, 'YOP_KOWEIT',        'Koweït', 10),
    (10, 'YOP_MAROC',         'Maroc', 11),

    -- Marcory
    (6, 'MAR_ZONE_4',       'Zone 4', 1),
    (6, 'MAR_BIETRY',       'Biétry', 2),
    (6, 'MAR_RESIDENTIEL',  'Marcory Résidentiel', 3),
    (6, 'MAR_ANOUMABO',     'Anoumabo', 4),
    (6, 'MAR_REMBLAIS',     'Remblais', 5),

    -- Treichville
    (9, 'TRE_ZONE_3',  'Zone 3', 1),
    (9, 'TRE_ARRAS',   'Arras', 2),
    (9, 'TRE_BIAFRA',  'Biafra', 3),
    (9, 'TRE_CENTRE',  'Treichville Centre', 4),

    -- Le Plateau
    (7, 'PLA_CENTRE',     'Plateau Centre', 1),
    (7, 'PLA_DOKUI',      'Plateau Dokui', 2),
    (7, 'PLA_CITE_ADM',   'Cité Administrative', 3),

    -- Abobo
    (1, 'ABO_CENTRE',      'Abobo Centre', 1),
    (1, 'ABO_BAOULE',      'Abobo Baoulé', 2),
    (1, 'ABO_SAGBE',       'Sagbé', 3),
    (1, 'ABO_ANADOR',      'Anador', 4),
    (1, 'ABO_AVOCATIER',   'Avocatier', 5),
    (1, 'ABO_PLAQUE',      'Plaque', 6),

    -- Adjamé
    (2, 'ADJ_220_LOG',   '220 Logements', 1),
    (2, 'ADJ_WILLIAMSV', 'Williamsville', 2),
    (2, 'ADJ_BRACODI',   'Bracodi', 3),
    (2, 'ADJ_LIBERTE',   'Liberté', 4),

    -- Koumassi
    (5, 'KOU_REMBLAIS',    'Koumassi Remblais', 1),
    (5, 'KOU_SICOGI',      'Sicogi', 2),
    (5, 'KOU_CAMPEMENT',   'Campement', 3),
    (5, 'KOU_GRAND_MARCHE','Grand Marché', 4),

    -- Port-Bouët
    (8, 'PBO_VRIDI',       'Vridi', 1),
    (8, 'PBO_GONZAGUEVILLE','Gonzagueville', 2),
    (8, 'PBO_ADJOUFFOU',   'Adjouffou', 3),
    (8, 'PBO_AEROPORT',    'Zone Aéroport', 4),

    -- Attécoubé
    (3, 'ATT_LOCODJORO',  'Locodjoro', 1),
    (3, 'ATT_SANTE',      'Attécoubé Santé', 2),
    (3, 'ATT_ABOBO_DOUME','Abobo-Doumé', 3),

    -- Bingerville, Anyama, Songon
    (12, 'BIN_CENTRE',  'Bingerville Centre', 1),
    (12, 'BIN_AKANDJE', 'Akandjé', 2),
    (11, 'ANY_CENTRE',  'Anyama Centre', 1),
    (13, 'SON_CENTRE',  'Songon Centre', 1),
    (13, 'SON_DAGBE',   'Songon Dagbé', 2);
