// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.http;

import com.google.common.base.Strings;
import com.starrocks.common.Config;
import com.starrocks.common.util.NetUtils;

/**
 * Decides which FE HTTP listener is used to reach an FE.
 * <p>
 * An FE can serve plain HTTP on {@code http_port} ({@code enable_http}) and HTTPS on {@code https_port}
 * ({@code enable_https}). A caller decides from its own configuration how to reach another FE, so the
 * listener settings and both ports must be the same on all FEs.
 */
public final class FrontendHttpEndpoint {
    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    private FrontendHttpEndpoint() {
    }

    /**
     * Checks the listener settings when the FE starts.
     *
     * @throws IllegalArgumentException if the FE would serve no HTTP listener at all, or would serve
     *                                  the HTTPS port without a keystore, that is, in plain text
     */
    public static void checkConfig() {
        if (!Config.enable_http && !Config.enable_https) {
            throw new IllegalArgumentException("enable_http and enable_https are both false. The FE needs at least "
                    + "one HTTP listener: set enable_http = true, or set enable_https = true and configure a keystore.");
        }
        if (Config.enable_https && Strings.isNullOrEmpty(Config.ssl_keystore_location)) {
            throw new IllegalArgumentException("enable_https is true but ssl_keystore_location is not set. "
                    + "Configure ssl_keystore_location, ssl_keystore_password and ssl_key_password, "
                    + "or set enable_https = false.");
        }
    }

    /**
     * Whether requests from one FE to another use HTTPS. They use the plain listener while it is enabled
     * and move to the HTTPS listener only when it is the one left.
     */
    public static boolean useHttps() {
        return !Config.enable_http;
    }

    /**
     * The scheme of requests from one FE to another.
     */
    public static String getScheme() {
        return useHttps() ? HTTPS : HTTP;
    }

    /**
     * The port of requests from one FE to another.
     */
    public static int getPort() {
        return useHttps() ? Config.https_port : Config.http_port;
    }

    /**
     * {@code scheme://host:port} for a request to another FE, without a trailing slash.
     */
    public static String getUrlPrefix(String host) {
        return getUrlPrefix(host, getPort());
    }

    /**
     * Same as {@link #getUrlPrefix(String)}, for a port that the other FE sent in a request.
     */
    public static String getUrlPrefix(String host, int port) {
        return getScheme() + "://" + NetUtils.getHostPortInAccessibleFormat(host, port);
    }

    /**
     * The FE HTTP port announced outside the FEs: to BEs and CNs (heartbeat and information_schema scans)
     * and in SHOW FRONTENDS. BEs and CNs have no HTTPS client, so this is the plain port while the plain
     * listener is enabled, whatever the FEs use among themselves.
     */
    public static int getAnnouncedPort() {
        return Config.enable_http ? Config.http_port : Config.https_port;
    }
}
