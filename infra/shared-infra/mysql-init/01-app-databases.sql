-- Applied only to an empty MySQL volume. Local development credentials.
CREATE DATABASE IF NOT EXISTS rag_study_helper CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'rag'@'%' IDENTIFIED BY 'rag-local-app-password';
GRANT ALL PRIVILEGES ON rag_study_helper.* TO 'rag'@'%';

CREATE DATABASE IF NOT EXISTS peizhen_app CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'peizhen'@'%' IDENTIFIED BY 'peizhen-local';
GRANT ALL PRIVILEGES ON peizhen_app.* TO 'peizhen'@'%';
