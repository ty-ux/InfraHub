CREATE TABLE sys_menu_layout (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    layout JSONB NOT NULL DEFAULT '{"groupOrder":[],"entries":[]}'
);

INSERT INTO sys_menu_layout (id) VALUES (1);
