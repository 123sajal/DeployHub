-- App folder: the folder inside the repository that holds the app (e.g. backend or frontend), so build and start
-- commands run there without "cd backend &&". Null = the repository root, as before. Additive only.
ALTER TABLE projects ADD (root_directory VARCHAR2(200));
