-- Version history of the restaurant's custom guest privacy notice PDFs (advisor C4).
-- Every upload inserts a new version; files and rows are never overwritten or deleted,
-- so the restaurant can prove which notice was published at any point in time.
CREATE TABLE IF NOT EXISTS guest_privacy_notices (
    version INT NOT NULL PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    uploaded_by VARCHAR(100) NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE
);
