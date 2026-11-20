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

import com.starrocks.persist.gson.RuntimeTypeAdapterFactory;
import com.starrocks.persist.gson.internal.RuntimeTypeAdapterTypes;
import com.starrocks.warehouse.Warehouse;

/**
 * Registers {@link UserWarehouse} with the FE's Gson, so it can read and write the class.
 *
 * <p>The image and the edit log store warehouses through the abstract {@code Warehouse} type, with a label that
 * names the concrete class. The FE keeps those labels in a public registry and reads it when it first builds the
 * adapter for {@code Warehouse}, so a label added here at extension load is in place before any metadata is read.
 */
final class WarehouseSubtypeRegistration {
    /**
     * Written into every persisted warehouse, so it can never change. It carries a namespace to stay clear of
     * any label the FE itself may introduce later.
     */
    static final String LABEL = "bi.deep.UserWarehouse";

    private static boolean registered;

    private WarehouseSubtypeRegistration() {
    }

    /**
     * Registers the label once per JVM. Further calls do nothing.
     *
     * @throws IllegalStateException when this FE has no registry for warehouses, or the label is taken
     */
    static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        RuntimeTypeAdapterFactory<?> registry =
                RuntimeTypeAdapterTypes.CLAZZ_TO_RUNTIME_TYPE_ADAPTOR_FACTORIES.get(Warehouse.class);
        register(registry);
        registered = true;
    }

    /**
     * @param registry the FE's subtype registry for {@code Warehouse}, or null when the FE has none
     */
    static void register(RuntimeTypeAdapterFactory<?> registry) {
        if (registry == null) {
            throw new IllegalStateException("This FE has no Gson subtype registry for " + Warehouse.class.getName()
                    + ". The warehouse extension does not support this FE version");
        }
        @SuppressWarnings("unchecked")
        RuntimeTypeAdapterFactory<Warehouse> warehouseRegistry = (RuntimeTypeAdapterFactory<Warehouse>) registry;
        try {
            warehouseRegistry.registerSubtype(UserWarehouse.class, LABEL);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot register the Gson label '" + LABEL + "' for "
                    + UserWarehouse.class.getName() + ": the label or the class is already registered", e);
        }
    }
}
