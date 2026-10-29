package com.deployhub.platform.support;

import com.deployhub.platform.services.Interface.IRepositoryFiles;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory repository for tests: {@link #files} are its files (path -> text). Remembers the token it was given;
 * {@link #acceptedToken} decides what checkAccess accepts (null = any) and {@link #notReadable} makes open fail.
 */
public class FakeRepositoryFiles implements IRepositoryFiles {

    public final Map<String, String> files = new ConcurrentHashMap<>();
    public volatile String lastToken;
    public volatile String acceptedToken;
    public volatile String notReadable;

    public void reset() {
        files.clear();
        lastToken = null;
        acceptedToken = null;
        notReadable = null;
    }

    @Override
    public Snapshot open(String repositoryUrl, String branch, boolean withSmallFiles, String token) {
        lastToken = token;
        if (notReadable != null) {
            throw new RepositoryNotReadable(notReadable);
        }
        return new Snapshot() {
            @Override
            public Set<String> paths() {
                return files.keySet();
            }

            @Override
            public Optional<String> read(String path) {
                return Optional.ofNullable(files.get(path));
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void checkAccess(String repositoryUrl, String branch, String token) {
        lastToken = token;
        if (acceptedToken != null && !acceptedToken.equals(token)) {
            throw new RepositoryNotReadable("The access token doesn't work for this repository: it may be wrong or "
                    + "expired, or it doesn't have read access to this repository.");
        }
    }
}
