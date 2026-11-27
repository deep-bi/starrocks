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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class FeVersionCheckTest {

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * The tests run against the fe-core the jar was compiled against, so both versions must be readable and
     * equal. This also proves the fe-core jar carries the Maven metadata the check relies on.
     */
    @Test
    public void versionsMatchInTheBuildEnvironment() {
        // A Maven run that stops before "package" (mvn test) puts fe-core on the classpath as a class
        // directory, which has no Maven metadata. There is nothing to compare then.
        String feCore = WarehouseManager.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        Assumptions.assumeTrue(feCore.endsWith(".jar"), "fe-core is not packaged in this build: " + feCore);

        Optional<String> running = FeVersionCheck.runningFeVersion();
        Assertions.assertTrue(running.isPresent(), "fe-core Maven metadata not found on the classpath");
        Assertions.assertEquals(running.get(), FeVersionCheck.builtForFeVersion());
        Assertions.assertDoesNotThrow(() -> FeVersionCheck.verify(running, FeVersionCheck.builtForFeVersion(), false));
    }

    @Test
    public void aMismatchIsRejected() {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> FeVersionCheck.verify(Optional.of("4.1.8"), "4.1.7", false));
        Assertions.assertTrue(e.getMessage().contains("4.1.7") && e.getMessage().contains("4.1.8"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains(WarehouseExtensionSettings.KEY_SKIP_VERSION_CHECK), e.getMessage());
    }

    @Test
    public void aMismatchCanBeSkipped() {
        Assertions.assertDoesNotThrow(() -> FeVersionCheck.verify(Optional.of("4.1.8"), "4.1.7", true));
    }

    /**
     * An FE whose fe-core jar has no Maven metadata cannot be checked. That must not block the start.
     */
    @Test
    public void anUnknownRunningVersionPasses() {
        Assertions.assertDoesNotThrow(() -> FeVersionCheck.verify(Optional.empty(), "4.1.7", false));
    }

    @Test
    public void aJarWithoutBuildInfoIsRefused() {
        Assertions.assertEquals("4.1.7", FeVersionCheck.requireBuildInfo(Optional.of("4.1.7")));

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> FeVersionCheck.requireBuildInfo(Optional.empty()));
        Assertions.assertTrue(e.getMessage().contains(FeVersionCheck.BUILD_INFO), e.getMessage());
    }

    @Test
    public void propertiesAreReadDefensively() {
        Assertions.assertEquals(Optional.of("4.1.7"), FeVersionCheck.readProperty(stream("version = 4.1.7 \n"), "version"));
        // No such resource, no such key, a blank value: all mean "unknown", never an exception.
        Assertions.assertEquals(Optional.empty(), FeVersionCheck.readProperty(null, "version"));
        Assertions.assertEquals(Optional.empty(), FeVersionCheck.readProperty(stream("groupId=com.starrocks\n"), "version"));
        Assertions.assertEquals(Optional.empty(), FeVersionCheck.readProperty(stream("version=   \n"), "version"));
    }

    @Test
    public void anUnreadableResourceMeansUnknown() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk error");
            }
        };

        Assertions.assertEquals(Optional.empty(), FeVersionCheck.readProperty(broken, "version"));
    }
}
