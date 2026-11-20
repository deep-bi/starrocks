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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.persist.gson.RuntimeTypeAdapterFactory;
import com.starrocks.warehouse.DefaultWarehouse;
import com.starrocks.warehouse.Warehouse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The image and the edit log store warehouses through the FE's shared Gson instance and the abstract
 * {@code Warehouse} type. These tests use that same instance, with the subtype registered the way the extension
 * does it: from outside, without changing the FE's registry class.
 */
public class WarehouseSubtypeRegistrationTest {

    @Test
    public void aUserDefinedWarehouseRoundTripsThroughTheAbstractType() {
        WarehouseSubtypeRegistration.ensureRegistered();
        // A second call must be a no-op, not a "label already registered" failure.
        WarehouseSubtypeRegistration.ensureRegistered();

        UserDefinedWarehouse original = new UserDefinedWarehouse(123L, "research", "ad hoc research", 456L, 2, 1_000L, 2_000L);

        String json = GsonUtils.GSON.toJson(original);
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        Assertions.assertEquals("bi.deep.warehouse", parsed.get("clazz").getAsString(), json);
        Assertions.assertEquals(UserDefinedWarehouse.FORMAT_VERSION, parsed.get("formatVersion").getAsInt(), json);

        Warehouse restored = GsonUtils.GSON.fromJson(json, Warehouse.class);
        Assertions.assertInstanceOf(UserDefinedWarehouse.class, restored);
        UserDefinedWarehouse warehouse = (UserDefinedWarehouse) restored;
        Assertions.assertEquals(123L, warehouse.getId());
        Assertions.assertEquals("research", warehouse.getName());
        Assertions.assertEquals("ad hoc research", warehouse.getComment());
        Assertions.assertEquals(456L, warehouse.getWorkerGroupId());
        Assertions.assertEquals(2, warehouse.getReplicaNumber());
        Assertions.assertEquals(1_000L, warehouse.getCreateTimeMs());
        Assertions.assertEquals(2_000L, warehouse.getUpdateTimeMs());
        Assertions.assertFalse(warehouse.isRetired());
    }

    /**
     * Every later jar has to read this persisted layout. If this literal has to change, raise
     * UserDefinedWarehouse.FORMAT_VERSION and keep reading the old layout.
     */
    @Test
    public void thePersistedLayoutIsStable() {
        WarehouseSubtypeRegistration.ensureRegistered();
        String stored = "{\"clazz\":\"bi.deep.warehouse\",\"formatVersion\":1,\"workerGroupId\":77,"
                + "\"replicaNumber\":3,\"createTimeMs\":1700000000000,\"updateTimeMs\":1700000001000,"
                + "\"name\":\"etl\",\"id\":10002,\"comment\":\"nightly loads\"}";

        UserDefinedWarehouse warehouse = (UserDefinedWarehouse) GsonUtils.GSON.fromJson(stored, Warehouse.class);

        Assertions.assertEquals(10002L, warehouse.getId());
        Assertions.assertEquals("etl", warehouse.getName());
        Assertions.assertEquals("nightly loads", warehouse.getComment());
        Assertions.assertEquals(77L, warehouse.getWorkerGroupId());
        Assertions.assertEquals(3, warehouse.getReplicaNumber());
        Assertions.assertEquals(1700000000000L, warehouse.getCreateTimeMs());
        Assertions.assertEquals(1700000001000L, warehouse.getUpdateTimeMs());

        JsonObject written = JsonParser.parseString(GsonUtils.GSON.toJson(warehouse)).getAsJsonObject();
        Assertions.assertEquals(JsonParser.parseString(stored), written);
    }

    /**
     * Metadata written by a newer jar must stop this one, through the FE's own post-read hook, instead of being
     * read with fields missing.
     */
    @Test
    public void aNewerLayoutIsRefusedWhenRead() {
        WarehouseSubtypeRegistration.ensureRegistered();
        String newer = "{\"clazz\":\"bi.deep.warehouse\",\"formatVersion\":" + (UserDefinedWarehouse.FORMAT_VERSION + 1)
                + ",\"workerGroupId\":77,\"replicaNumber\":3,\"name\":\"etl\",\"id\":10002}";

        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> GsonUtils.GSON.fromJson(newer, Warehouse.class));

        Assertions.assertTrue(String.valueOf(e).contains("is stored in format") || String.valueOf(e.getCause())
                .contains("is stored in format"), e + " / " + e.getCause());
    }

    /**
     * Registering the extra subtype must not disturb the stock one.
     */
    @Test
    public void theDefaultWarehouseStillRoundTrips() {
        WarehouseSubtypeRegistration.ensureRegistered();

        String json = GsonUtils.GSON.toJson(new DefaultWarehouse(0L, "default_warehouse"));

        Assertions.assertInstanceOf(DefaultWarehouse.class, GsonUtils.GSON.fromJson(json, Warehouse.class));
    }

    @Test
    public void anFeWithoutAWarehouseRegistryIsRefused() {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> WarehouseSubtypeRegistration.register(null));

        Assertions.assertTrue(e.getMessage().contains("no Gson subtype registry"), e.getMessage());
    }

    /**
     * If the FE, or another extension, already uses the label or the class, loading must fail. Two
     * owners of one label would read each other's metadata.
     */
    @Test
    public void aTakenLabelIsRefused() {
        RuntimeTypeAdapterFactory<Warehouse> registry = RuntimeTypeAdapterFactory.of(Warehouse.class, "clazz");
        registry.registerSubtype(DefaultWarehouse.class, WarehouseSubtypeRegistration.LABEL);

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> WarehouseSubtypeRegistration.register(registry));

        Assertions.assertTrue(e.getMessage().contains(WarehouseSubtypeRegistration.LABEL), e.getMessage());
        Assertions.assertInstanceOf(IllegalArgumentException.class, e.getCause());

        RuntimeTypeAdapterFactory<Warehouse> fresh = RuntimeTypeAdapterFactory.of(Warehouse.class, "clazz");
        Assertions.assertDoesNotThrow(() -> WarehouseSubtypeRegistration.register(fresh));
        Assertions.assertThrows(IllegalStateException.class, () -> WarehouseSubtypeRegistration.register(fresh));
    }
}
