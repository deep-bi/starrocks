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
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;

/**
 * The warehouse errors that have an FE error code.
 *
 * <p>Each method returns the exception for the caller to throw. Besides building the message,
 * {@code ErrorReport.reportCommon} records the error code on the current connection, so a MySQL client sees the
 * same code as for the FE's own warehouse errors.
 */
final class WarehouseErrors {
    private WarehouseErrors() {
    }

    static DdlException unknownWarehouse(String name) {
        return coded(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("name: %s", name));
    }

    static DdlException warehouseExists(String name) {
        return coded(ErrorCode.ERR_WAREHOUSE_EXISTS, name);
    }

    static DdlException cnGroupsNotImplemented() {
        return coded(ErrorCode.ERR_CNGROUP_NOT_IMPLEMENTED);
    }

    private static DdlException coded(ErrorCode code, Object... args) {
        return new DdlException(ErrorReport.reportCommon(null, code, args));
    }
}
