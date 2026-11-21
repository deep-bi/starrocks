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

package bi.deep.starrocks.extension.warehouse;

import com.starrocks.server.WarehouseManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Checks that the extension runs on the FE version it was compiled against.
 *
 * <p>The extension calls FE classes that are not a public API. A extension built for another version can load
 * and then fail with {@code NoSuchMethodError} on the first warehouse statement, so the mismatch is caught at
 * start instead.
 *
 * <p>The running version is read from the Maven metadata inside the fe-core jar. {@code Version.STARROCKS_VERSION}
 * is not usable for this: depending on how the FE was built it holds a tag, a branch name or a commit hash.
 */
final class FeVersionCheck {
    static final String FE_CORE_POM_PROPERTIES = "/META-INF/maven/com.starrocks/fe-core/pom.properties";
    static final String BUILD_INFO = "/bi/deep/starrocks/extension/warehouse/build-info.properties";
    static final String BUILD_INFO_FE_VERSION = "fe.version";

    private FeVersionCheck() {
    }

    /**
     * @return the version of the fe-core jar on the classpath, or empty when it carries no Maven metadata
     */
    static Optional<String> runningFeVersion() {
        return readProperty(WarehouseManager.class.getResourceAsStream(FE_CORE_POM_PROPERTIES), "version");
    }

    /**
     * @return the FE version this extension was compiled against, recorded by the build
     */
    static String builtForFeVersion() {
        return requireBuildInfo(
                readProperty(FeVersionCheck.class.getResourceAsStream(BUILD_INFO), BUILD_INFO_FE_VERSION));
    }

    static String requireBuildInfo(Optional<String> feVersion) {
        return feVersion.orElseThrow(() -> new IllegalStateException(
                "The extension jar is missing " + BUILD_INFO + "; it was not produced by the project build"));
    }

    /**
     * @throws IllegalStateException when both versions are known and differ, unless {@code skip} is set
     */
    static void verify(Optional<String> runningFeVersion, String builtForFeVersion, boolean skip) {
        if (skip) {
            return;
        }
        if (runningFeVersion.isPresent() && !runningFeVersion.get().equals(builtForFeVersion)) {
            throw new IllegalStateException(
                    "The warehouse extension was built for StarRocks FE " + builtForFeVersion
                            + " but this FE is " + runningFeVersion.get()
                            + ". Install the jar built for this version, or set "
                            + WarehouseExtensionSettings.KEY_SKIP_VERSION_CHECK + " = true in fe.conf to run it anyway");
        }
    }

    /**
     * @param in a properties file, or null when the resource does not exist; closed before returning
     * @return the trimmed value of {@code key}, or empty when it is missing, blank or unreadable
     */
    static Optional<String> readProperty(InputStream in, String key) {
        if (in == null) {
            return Optional.empty();
        }
        try (InputStream stream = in) {
            Properties props = new Properties();
            props.load(stream);
            String value = props.getProperty(key);
            return value == null || value.trim().isEmpty() ? Optional.empty() : Optional.of(value.trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
