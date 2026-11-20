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
import com.starrocks.extension.ExtensionContext;
import com.starrocks.extension.SRModule;
import com.starrocks.extension.StarRocksExtension;
import com.starrocks.server.WarehouseManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Entry point of the Warehouse Extension for StarRocks.
 *
 * <p>At start the FE scans {@code Config.ext_dir} (fe/lib by default) for files whose name ends in
 * {@code ext.jar}, instantiates every class carrying {@link SRModule} and calls {@link #onLoad}. That happens
 * after fe.conf is read and before {@code GlobalStateMgr} is built, and {@code GlobalStateMgr} asks the same
 * context for its {@code WarehouseManager}. Registering {@link UserWarehouseManager} here therefore replaces the
 * stock manager without any change to FE code.
 */
@SRModule(name = WarehouseExtension.NAME)
public class WarehouseExtension implements StarRocksExtension {
    public static final String NAME = "warehouse_extension";

    private static final Logger LOG = LogManager.getLogger(WarehouseExtension.class);
    private static final String SHARED_DATA = "shared_data";

    private final Supplier<Path> feConf;
    private final IntConsumer exit;

    /**
     * The constructor the FE calls.
     */
    public WarehouseExtension() {
        this(WarehouseExtensionSettings::defaultFeConfPath, System::exit);
    }

    /**
     * @param feConf where fe.conf is
     * @param exit   stops the process with the given status
     */
    WarehouseExtension(Supplier<Path> feConf, IntConsumer exit) {
        this.feConf = feConf;
        this.exit = exit;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        try {
            load(ctx, WarehouseExtensionSettings.loadFromFile(feConf.get()));
        } catch (Throwable t) {
            // The FE's ExtensionManager only logs a warning when an extension throws, and then carries on with
            // the stock WarehouseManager. The FE then skips the warehouse journal entries it cannot read, and
            // that manager does not read persisted warehouses, so they would vanish while compute nodes still
            // point at them. Stop the FE.
            String message = "Failed to load the " + NAME + ", stopping the FE: " + t;
            LOG.fatal(message, t);
            System.err.println(message);
            exit.accept(-1);
        }
    }

    /**
     * Everything {@link #onLoad} does, without reading fe.conf and without the process exit.
     *
     * @return true when {@link UserWarehouseManager} was registered
     */
    boolean load(ExtensionContext ctx, WarehouseExtensionSettings settings) {
        return load(ctx, settings, FeVersionCheck.runningFeVersion(), FeVersionCheck.builtForFeVersion());
    }

    /**
     * @param runningFeVersion  the version of the FE this jar is loaded into, empty when it cannot be read
     * @param builtForFeVersion the FE version this jar was compiled against
     */
    boolean load(ExtensionContext ctx, WarehouseExtensionSettings settings, Optional<String> runningFeVersion,
                 String builtForFeVersion) {
        if (!SHARED_DATA.equalsIgnoreCase(Config.run_mode)) {
            // A warehouse is a StarMgr worker group, which exists only in shared-data mode. Leave shared-nothing
            // FEs as they are.
            LOG.info("{}: run_mode is {}, keeping the stock WarehouseManager", NAME, Config.run_mode);
            return false;
        }

        FeVersionCheck.verify(runningFeVersion, builtForFeVersion, settings.isSkipVersionCheck());
        if (runningFeVersion.isEmpty()) {
            LOG.warn("{}: cannot determine the running FE version, version check skipped", NAME);
        }

        requireStockWarehouseManager(ctx);
        WarehouseSubtypeRegistration.ensureRegistered();
        WarehouseExtensionSettings.install(settings);
        // registerConstructor, not register: the context then builds a new manager per lookup. The checkpoint
        // thread has its own GlobalStateMgr, and a shared instance would let checkpoint replay rewrite the
        // warehouse maps of the serving state.
        ctx.registerConstructor(WarehouseManager.class, UserWarehouseManager.class);

        LOG.info("{} loaded: WarehouseManager -> {}, built for FE {}, running FE {}, {}",
                NAME, UserWarehouseManager.class.getName(), builtForFeVersion,
                runningFeVersion.orElse("unknown"), settings);
        return true;
    }

    /**
     * Refuses to take over when something else has already replaced the FE's warehouse manager: another
     * extension, or an FE that ships its own implementation and has it switched on. Two implementations would
     * write different metadata under the same edit-log operations.
     */
    private static void requireStockWarehouseManager(ExtensionContext ctx) {
        Class<?> active = ctx.get(WarehouseManager.class).getClass();
        if (active != WarehouseManager.class && active != UserWarehouseManager.class) {
            throw new IllegalStateException("This FE already uses " + active.getName() + " as its warehouse"
                    + " manager. Remove the warehouse extension jar, or switch the other implementation off");
        }
    }
}
