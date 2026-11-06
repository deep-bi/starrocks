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

package com.starrocks.backup;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

// One file of a tablet in a snapshot's tablet manifest, and the snapshot whose directory holds it.
public class ManifestEntry {
    // <name>.<md5>, as the BE names uploaded files
    @SerializedName(value = "file")
    private String file;
    @SerializedName(value = "holder")
    private String holder;
    // Incremental snapshots keep their own files under a different content directory than full ones.
    @SerializedName(value = "holderIncremental")
    private boolean holderIncremental;

    public ManifestEntry(String file, String holder, boolean holderIncremental) {
        this.file = file;
        this.holder = holder;
        this.holderIncremental = holderIncremental;
    }

    public String getFile() {
        return file;
    }

    public String getHolder() {
        return holder;
    }

    public boolean isHolderIncremental() {
        return holderIncremental;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ManifestEntry)) {
            return false;
        }
        ManifestEntry that = (ManifestEntry) o;
        return holderIncremental == that.holderIncremental && file.equals(that.file) && holder.equals(that.holder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, holder, holderIncremental);
    }

    @Override
    public String toString() {
        return file + "@" + holder;
    }
}
