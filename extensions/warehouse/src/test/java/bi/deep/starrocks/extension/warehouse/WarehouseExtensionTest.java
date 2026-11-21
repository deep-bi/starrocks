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

import com.starrocks.common.Config;
import com.starrocks.extension.DefaultExtensionContext;
import com.starrocks.extension.SRModule;
import com.starrocks.extension.StarRocksExtension;
import com.starrocks.server.WarehouseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Exercises the extension against the FE's real extension context, the same object {@code GlobalStateMgr} asks
 * for its {@code WarehouseManager}.
 */
public class WarehouseExtensionTest {
    private static final WarehouseExtensionSettings ON = WarehouseExtensionSettings.of(true, false);

    /** Stands in for a warehouse manager that someone else has installed. */
    public static class SomeoneElsesWarehouseManager extends WarehouseManager {
        public SomeoneElsesWarehouseManager() {
            super();
        }
    }

    private String savedRunMode;

    @BeforeEach
    public void before() {
        savedRunMode = Config.run_mode;
        Config.run_mode = "shared_data";
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    @AfterEach
    public void after() {
        Config.run_mode = savedRunMode;
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    /**
     * The FE discovers the class by these two properties, and builds it with a no-arg constructor.
     */
    @Test
    public void isDiscoverableByTheFe() throws Exception {
        Assertions.assertTrue(StarRocksExtension.class.isAssignableFrom(WarehouseExtension.class));
        SRModule module = WarehouseExtension.class.getAnnotation(SRModule.class);
        Assertions.assertNotNull(module);
        Assertions.assertEquals("warehouse_extension", module.name());
        Assertions.assertNotNull(WarehouseExtension.class.getDeclaredConstructor().newInstance());
    }

    @Test
    public void theStockContextBuildsTheStockManager() {
        DefaultExtensionContext ctx = new DefaultExtensionContext();

        Assertions.assertSame(WarehouseManager.class, ctx.get(WarehouseManager.class).getClass());
    }

    @Test
    public void loadReplacesTheWarehouseManager() {
        DefaultExtensionContext ctx = new DefaultExtensionContext();

        boolean registered = new WarehouseExtension().load(ctx, ON);

        Assertions.assertTrue(registered);
        WarehouseManager first = ctx.get(WarehouseManager.class);
        WarehouseManager second = ctx.get(WarehouseManager.class);
        Assertions.assertInstanceOf(UserWarehouseManager.class, first);
        Assertions.assertInstanceOf(UserWarehouseManager.class, second);
        // The serving GlobalStateMgr and the checkpoint GlobalStateMgr each look the manager up once. They must
        // not end up sharing one instance.
        Assertions.assertNotSame(first, second);
        Assertions.assertTrue(WarehouseExtensionSettings.current().isEnabled());
    }

    /**
     * With the jar present but the setting off, the manager is still replaced so persisted warehouses stay
     * readable. Only creation is refused, which UserWarehouseManagerTest covers.
     */
    @Test
    public void loadWithTheSettingOffStillReplacesTheManager() {
        DefaultExtensionContext ctx = new DefaultExtensionContext();

        Assertions.assertTrue(new WarehouseExtension().load(ctx, WarehouseExtensionSettings.defaults()));

        Assertions.assertInstanceOf(UserWarehouseManager.class, ctx.get(WarehouseManager.class));
        Assertions.assertFalse(WarehouseExtensionSettings.current().isEnabled());
    }

    @Test
    public void sharedNothingIsLeftUntouched() {
        Config.run_mode = "shared_nothing";
        DefaultExtensionContext ctx = new DefaultExtensionContext();

        boolean registered = new WarehouseExtension().load(ctx, ON);

        Assertions.assertFalse(registered);
        Assertions.assertSame(WarehouseManager.class, ctx.get(WarehouseManager.class).getClass());
        Assertions.assertFalse(WarehouseExtensionSettings.current().isEnabled());
    }

    @Test
    public void aJarBuiltForAnotherFeVersionIsRefused() {
        DefaultExtensionContext ctx = new DefaultExtensionContext();

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> new WarehouseExtension().load(ctx, ON, Optional.of("4.1.8"), "4.1.7"));

        Assertions.assertTrue(e.getMessage().contains("built for StarRocks FE 4.1.7"), e.getMessage());
        Assertions.assertSame(WarehouseManager.class, ctx.get(WarehouseManager.class).getClass());
        Assertions.assertFalse(WarehouseExtensionSettings.current().isEnabled());
    }

    @Test
    public void theVersionCheckCanBeSkippedOrIsMootWhenTheFeVersionIsUnknown() {
        DefaultExtensionContext skipped = new DefaultExtensionContext();
        Assertions.assertTrue(new WarehouseExtension()
                .load(skipped, WarehouseExtensionSettings.of(true, true), Optional.of("4.1.8"), "4.1.7"));
        Assertions.assertInstanceOf(UserWarehouseManager.class, skipped.get(WarehouseManager.class));

        DefaultExtensionContext unknown = new DefaultExtensionContext();
        Assertions.assertTrue(new WarehouseExtension().load(unknown, ON, Optional.empty(), "4.1.7"));
        Assertions.assertInstanceOf(UserWarehouseManager.class, unknown.get(WarehouseManager.class));
    }

    /**
     * Another extension, or an FE with its own implementation switched on, has already replaced the manager.
     * Taking over would put two metadata formats under the same edit-log operations.
     */
    @Test
    public void anotherActiveImplementationIsNotOverridden() {
        DefaultExtensionContext ctx = new DefaultExtensionContext();
        ctx.registerConstructor(WarehouseManager.class, SomeoneElsesWarehouseManager.class);

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> new WarehouseExtension().load(ctx, ON));

        Assertions.assertTrue(e.getMessage().contains(SomeoneElsesWarehouseManager.class.getName()), e.getMessage());
        Assertions.assertInstanceOf(SomeoneElsesWarehouseManager.class, ctx.get(WarehouseManager.class));
        Assertions.assertFalse(WarehouseExtensionSettings.current().isEnabled());
    }

    @Test
    public void loadingTwiceIsHarmless() {
        DefaultExtensionContext ctx = new DefaultExtensionContext();
        WarehouseExtension extension = new WarehouseExtension();

        Assertions.assertTrue(extension.load(ctx, ON));
        Assertions.assertTrue(extension.load(ctx, ON));

        Assertions.assertInstanceOf(UserWarehouseManager.class, ctx.get(WarehouseManager.class));
    }

    // ------------------------------------------------------------------ onLoad: what the FE calls

    private static Path feConf(Path dir, String... lines) throws Exception {
        Path file = dir.resolve("fe.conf");
        Files.write(file, Arrays.asList(lines), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void onLoadReadsItsSettingsFromFeConf(@TempDir Path dir) throws Exception {
        Path file = feConf(dir, "run_mode = shared_data", "enable_warehouse_extension = true");
        List<Integer> exits = new ArrayList<>();
        DefaultExtensionContext ctx = new DefaultExtensionContext();

        new WarehouseExtension(() -> file, exits::add).onLoad(ctx);

        Assertions.assertEquals(Collections.emptyList(), exits);
        Assertions.assertInstanceOf(UserWarehouseManager.class, ctx.get(WarehouseManager.class));
        Assertions.assertTrue(WarehouseExtensionSettings.current().isEnabled());
    }

    /**
     * The FE only logs a warning when an extension throws, and then starts with the stock manager, which loses
     * persisted warehouses. So every failure must stop the process instead.
     */
    @Test
    public void onLoadStopsTheFeOnAnyFailure(@TempDir Path dir) throws Exception {
        List<Integer> exits = new ArrayList<>();

        // A typo in the setting.
        Path typo = feConf(dir, "enable_warehouse_extension = ture");
        DefaultExtensionContext ctx = new DefaultExtensionContext();
        new WarehouseExtension(() -> typo, exits::add).onLoad(ctx);
        Assertions.assertEquals(Collections.singletonList(-1), exits);
        Assertions.assertSame(WarehouseManager.class, ctx.get(WarehouseManager.class).getClass());

        // No fe.conf at all.
        new WarehouseExtension(() -> dir.resolve("missing.conf"), exits::add).onLoad(new DefaultExtensionContext());
        Assertions.assertEquals(Arrays.asList(-1, -1), exits);

        // The location of fe.conf cannot be worked out.
        new WarehouseExtension(() -> {
            throw new IllegalStateException("STARROCKS_HOME is not set");
        }, exits::add).onLoad(new DefaultExtensionContext());
        Assertions.assertEquals(Arrays.asList(-1, -1, -1), exits);

        // Another implementation is active.
        DefaultExtensionContext taken = new DefaultExtensionContext();
        taken.registerConstructor(WarehouseManager.class, SomeoneElsesWarehouseManager.class);
        Path ok = feConf(dir, "enable_warehouse_extension = true");
        new WarehouseExtension(() -> ok, exits::add).onLoad(taken);
        Assertions.assertEquals(Arrays.asList(-1, -1, -1, -1), exits);
    }
}
