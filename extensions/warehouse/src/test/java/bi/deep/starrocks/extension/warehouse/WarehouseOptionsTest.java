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

import com.starrocks.common.DdlException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class WarehouseOptionsTest {

    private static Map<String, String> one(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    public void createWithoutPropertiesUsesTheDefault() throws Exception {
        Assertions.assertEquals(WarehouseOptions.DEFAULT_REPLICA_NUMBER, WarehouseOptions.forCreate(null).replicaNumber());
        Assertions.assertEquals(1, WarehouseOptions.forCreate(Collections.emptyMap()).replicaNumber());
    }

    @Test
    public void replicaNumberIsParsedLeniently() throws Exception {
        Assertions.assertEquals(3, WarehouseOptions.forCreate(one("replica_number", "3")).replicaNumber());
        Assertions.assertEquals(3, WarehouseOptions.forCreate(one("replica_number", " 3 ")).replicaNumber());
        Assertions.assertEquals(3, WarehouseOptions.forCreate(one(" Replica_Number ", "3")).replicaNumber());
    }

    @Test
    public void badReplicaNumbersAreRejected() {
        for (String bad : new String[] {"0", "-1", "abc", "", " ", "1.5", "2147483648", null}) {
            DdlException e = Assertions.assertThrows(DdlException.class,
                    () -> WarehouseOptions.forCreate(one("replica_number", bad)), "value: " + bad);
            Assertions.assertTrue(e.getMessage().contains("Invalid replica_number"), e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("'" + bad + "'"), e.getMessage());
        }
    }

    @Test
    public void unknownPropertiesAreRejectedNotIgnored() {
        DdlException e = Assertions.assertThrows(DdlException.class, () -> WarehouseOptions.forCreate(one("size", "x1")));
        Assertions.assertTrue(e.getMessage().contains("Unknown warehouse property [size]"), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("Supported: replica_number"), e.getMessage());

        Map<String, String> mixed = one("replica_number", "2");
        mixed.put("compute_replica", "1");
        Assertions.assertThrows(DdlException.class, () -> WarehouseOptions.forCreate(mixed));

        // A null key cannot come from the parser, but it must not slip through as "no property".
        Assertions.assertThrows(DdlException.class, () -> WarehouseOptions.forCreate(one(null, "1")));
    }

    @Test
    public void theSamePropertyTwiceIsRejected() {
        Map<String, String> twice = new LinkedHashMap<>();
        twice.put("replica_number", "1");
        twice.put("REPLICA_NUMBER", "2");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> WarehouseOptions.forCreate(twice));

        Assertions.assertTrue(e.getMessage().contains("'replica_number' is given more than once"), e.getMessage());
    }

    @Test
    public void alterNeedsAtLeastOneProperty() {
        Assertions.assertThrows(DdlException.class, () -> WarehouseOptions.forAlter(null, 2));
        DdlException e = Assertions.assertThrows(DdlException.class,
                () -> WarehouseOptions.forAlter(Collections.emptyMap(), 2));
        Assertions.assertTrue(e.getMessage().contains("at least one property"), e.getMessage());
    }

    @Test
    public void alterSetsWhatIsGiven() throws Exception {
        Assertions.assertEquals(5, WarehouseOptions.forAlter(one("replica_number", "5"), 2).replicaNumber());
        Assertions.assertThrows(DdlException.class, () -> WarehouseOptions.forAlter(one("comment", "x"), 2));
        Assertions.assertThrows(DdlException.class, () -> WarehouseOptions.forAlter(one("replica_number", "0"), 2));
    }

    @Test
    public void describeIsWhatShowWarehousesPrints() {
        Assertions.assertEquals("replica_number=2", WarehouseOptions.describe(2));
    }
}
