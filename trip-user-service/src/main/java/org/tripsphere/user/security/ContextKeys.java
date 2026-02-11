package org.tripsphere.user.security;

import io.grpc.Context;

/** Context keys for storing user information in gRPC context. */
public final class ContextKeys {

    /** Context key for storing username. */
    public static final Context.Key<String> USERNAME_KEY = Context.key("username");

    private ContextKeys() {
        // Utility class
    }
}
