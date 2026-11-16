-- =============================================================================
-- L'assistant IA : la mémoire de la conversation.
-- Le modèle n'a aucune mémoire : à chaque question, l'API relit ici les derniers
-- messages du compte et les lui renvoie avec la nouvelle question.
-- Chaque ligne appartient à UN compte : on filtre toujours par id_compte.
-- =============================================================================

CREATE TABLE app.assistant_message (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_compte  integer     NOT NULL REFERENCES app.compte ON DELETE CASCADE,
    role       varchar(10) NOT NULL,
    texte      text        NOT NULL,
    cree_le    timestamptz NOT NULL DEFAULT now(),
    -- « user » : la personne ; « assistant » : la réponse du modèle (mêmes mots que l'API d'OpenAI).
    CONSTRAINT chk_assistant_message_role CHECK (role IN ('user', 'assistant'))
);

CREATE INDEX idx_assistant_message_compte ON app.assistant_message (id_compte, id DESC);

COMMENT ON TABLE app.assistant_message IS 'Historique des conversations avec l''assistant IA, un fil par compte.';
