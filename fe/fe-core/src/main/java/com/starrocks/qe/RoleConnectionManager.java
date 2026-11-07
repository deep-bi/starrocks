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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/qe/ConnectScheduler.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.qe;

import com.google.common.annotations.VisibleForTesting;
import com.starrocks.authorization.RolePrivilegeCollectionV2;
import com.starrocks.common.CloseableLock;
import com.starrocks.common.Pair;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Limits the concurrent connections per role, as configured by qe_role_connection_limits.
 *
 * <p>A connection is counted against every limited role active in its session when it is registered. If any of
 * those roles is already at its limit, the connection is rejected and nothing is counted. The roles a connection
 * was counted against are remembered, so closing it releases exactly those, even after SET ROLE changed the roles
 * of the session.
 */
public class RoleConnectionManager {
    private static final Logger LOG = LogManager.getLogger(RoleConnectionManager.class);
    private final Map<String, Integer> connCountByRole = new HashMap<>();
    // the limited roles each registered connection is counted against
    private final Map<ConnectContext, Set<String>> rolesByConnection = new IdentityHashMap<>();
    private final ReentrantLock connLock = new ReentrantLock();
    private final List<RoleConnectionLimit> roleConnectionLimits;

    public RoleConnectionManager(String config) {
        this.roleConnectionLimits = parse(config);
    }

    /**
     * Count the connection against the limits of its active roles. A connection is counted once, registering it
     * again has no effect. A connection whose user is not authenticated yet is not counted, so it must be
     * registered again once it is.
     *
     * @param ctx connection context
     * @return a pair, first is success or not, second is error message(if any)
     */
    public Pair<Boolean, String> registerConnection(ConnectContext ctx) {
        if (roleConnectionLimits.isEmpty() || ctx.getCurrentUserIdentity() == null) {
            return new Pair<>(true, null);
        }

        Map<String, Integer> maxLimitForRoles = getMaxLimitForRoles(ctx);
        try (CloseableLock ignored = CloseableLock.lock(this.connLock)) {
            if (rolesByConnection.containsKey(ctx)) {
                return new Pair<>(true, null);
            }

            String errMsg = checkLimits(ctx, maxLimitForRoles, Set.of());
            if (errMsg != null) {
                return new Pair<>(false, errMsg);
            }
            this.registerConnection(ctx, maxLimitForRoles.keySet());
            return new Pair<>(true, null);
        }
    }

    /**
     * Move a connection that switched to another user over to the roles of that user. A rejected switch leaves the
     * connection counted against its previous roles.
     *
     * @param ctx connection context that already carries the new user and roles
     * @return a pair, first is success or not, second is error message(if any)
     */
    public Pair<Boolean, String> onUserChanged(ConnectContext ctx) {
        if (roleConnectionLimits.isEmpty()) {
            return new Pair<>(true, null);
        }

        Map<String, Integer> maxLimitForRoles = getMaxLimitForRoles(ctx);
        try (CloseableLock ignored = CloseableLock.lock(this.connLock)) {
            Set<String> previousRoles = rolesByConnection.getOrDefault(ctx, Set.of());
            // a role the connection is already counted against needs no new slot
            String errMsg = checkLimits(ctx, maxLimitForRoles, previousRoles);
            if (errMsg != null) {
                return new Pair<>(false, errMsg);
            }
            this.unregisterConnection(previousRoles);
            this.registerConnection(ctx, maxLimitForRoles.keySet());
            return new Pair<>(true, null);
        }
    }

    public void unregisterConnection(ConnectContext ctx) {
        try (CloseableLock ignored = CloseableLock.lock(this.connLock)) {
            Set<String> roles = rolesByConnection.remove(ctx);
            if (roles != null) {
                this.unregisterConnection(roles);
            }
        }
    }

    @VisibleForTesting
    public int getCurrentConnection(String role) {
        try (CloseableLock ignored = CloseableLock.lock(this.connLock)) {
            return connCountByRole.getOrDefault(role, 0);
        }
    }

    private String checkLimits(ConnectContext ctx, Map<String, Integer> maxLimitForRoles, Set<String> countedRoles) {
        for (Map.Entry<String, Integer> entry : maxLimitForRoles.entrySet()) {
            String role = entry.getKey();
            if (countedRoles.contains(role)) {
                continue;
            }

            int currentConnection = connCountByRole.getOrDefault(role, 0);
            int limit = entry.getValue();
            if (currentConnection >= limit) {
                String userErrMsg = String.format("Role-based connection limit reached "
                                + "[user=%s, role=%s, maxAllowed=%s, activeConnections=%s, node=%s, connectionId=%s]",
                        ctx.getQualifiedUser(),
                        role,
                        limit,
                        currentConnection,
                        ctx.getGlobalStateMgr().getNodeMgr().getSelfNode(),
                        ctx.getConnectionId());
                LOG.info("{}, details: userRoles={}", userErrMsg, maxLimitForRoles.keySet());
                return userErrMsg;
            }
        }
        return null;
    }

    private void registerConnection(ConnectContext ctx, Set<String> roles) {
        roles.forEach(role -> connCountByRole.merge(role, 1, Integer::sum));
        rolesByConnection.put(ctx, Set.copyOf(roles));
    }

    private void unregisterConnection(Set<String> roles) {
        roles.forEach(role -> connCountByRole.computeIfPresent(role, (k, v) -> v > 1 ? v - 1 : null));
    }

    // the active roles of the session that have a limit, with that limit
    private Map<String, Integer> getMaxLimitForRoles(ConnectContext ctx) {
        Map<String, Integer> result = new HashMap<>();
        if (ctx.getCurrentRoleIds() == null) {
            return result;
        }

        ctx.getCurrentRoleIds().stream()
                .map(ctx.getGlobalStateMgr().getAuthorizationMgr()::getRolePrivilegeCollection)
                .filter(Objects::nonNull)
                .map(RolePrivilegeCollectionV2::getName)
                .forEach(role -> {
                    for (RoleConnectionLimit rcl : roleConnectionLimits) {
                        if (rcl.matches(role)) {
                            result.put(role, rcl.maxConnections);
                            break;
                        }
                    }
                });
        return result;
    }

    private record RoleConnectionLimit(Pattern rolePattern, int maxConnections, int specificity) {

        public boolean matches(String role) {
            return rolePattern.matcher(role).matches();
        }
    }

    private static List<RoleConnectionLimit> parse(String config) {
        if (StringUtils.isBlank(config)) {
            return List.of();
        }

        return Stream.of(config.split(","))
            .map(String::trim)
            .map(RoleConnectionManager::parseEntry)
            .sorted(Comparator.comparingInt(RoleConnectionLimit::specificity).reversed())
            .toList();
    }

    private static RoleConnectionLimit parseEntry(String entry) {
        int sep = entry.indexOf(':');

        if (sep <= 0 || sep == entry.length() - 1) {
            throw new IllegalArgumentException("Invalid role connection limit entry: " + entry);
        }

        String regex = wildcardToRegex(entry.substring(0, sep).trim());
        int specificity = calculateSpecificity(regex);
        int maxConnections = Integer.parseInt(entry.substring(sep + 1).trim());

        return new RoleConnectionLimit(Pattern.compile(regex), maxConnections, specificity);
    }

    /**
     * Converts wildcard patterns like ROLE_AD_* → ROLE_AD_.*
     */
    private static String wildcardToRegex(String value) {
        return value.replace(".", "\\.").replace("*", ".*");
    }

    /**
     * Higher number = more specific
     * Counts the literal characters: removes wildcards and regex noise
     */
    private static int calculateSpecificity(String pattern) {
        return pattern.replace(".*", "").replace("\\.", ".").length();
    }
}
