package com.deployhub.platform.services.Interface;

import java.util.Optional;
import java.util.Set;

/** Read-only look at one branch of a public repository, without checking it out. */
public interface IRepositoryFiles {

    /**
     * Fetches the branch's file list (not the files themselves).
     *
     * @throws RepositoryNotReadable when the repository or branch doesn't exist or isn't public
     */
    default Snapshot open(String repositoryUrl, String branch) {
        return open(repositoryUrl, branch, false, null);
    }

    /**
     * @param withSmallFiles also download every file up to 256 KB in the same fetch, so reading many files (e.g. to
     *                       scan the source code) needs no further requests
     * @param token          access token for a private repository; null for public ones
     */
    Snapshot open(String repositoryUrl, String branch, boolean withSmallFiles, String token);

    /**
     * Checks that the repository and branch can be read (with the token, for a private repository) without
     * downloading anything.
     *
     * @throws RepositoryNotReadable with a plain explanation when they can't
     */
    void checkAccess(String repositoryUrl, String branch, String token);

    interface Snapshot extends AutoCloseable {

        /** Every file path in the branch, e.g. "backend/package.json". */
        Set<String> paths();

        /** A file's text (fetched on demand); empty when it doesn't exist or is too large. */
        Optional<String> read(String path);

        @Override
        void close();
    }

    /** The repository or branch can't be read; the message says why in plain words. */
    class RepositoryNotReadable extends RuntimeException {
        public RepositoryNotReadable(String message) {
            super(message);
        }
    }
}
