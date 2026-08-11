UPDATE documents d
JOIN document_versions v
  ON v.document_id = d.id
 AND v.version_number = d.current_version
SET d.pending_version = NULL,
    d.update_time = CURRENT_TIMESTAMP
WHERE d.status = 'READY'
  AND d.pending_version = d.current_version
  AND v.status = 'READY';
