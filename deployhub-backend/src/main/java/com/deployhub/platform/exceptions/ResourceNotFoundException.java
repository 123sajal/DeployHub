package com.deployhub.platform.exceptions;

/** Also thrown when the resource exists but belongs to another user, so ids can't be probed. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " " + id + " was not found");
    }
}
