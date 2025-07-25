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

package com.starrocks.qe.scheduler;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.load.loadv2.LoadJob;
import com.starrocks.qe.DefaultCoordinator;
import com.starrocks.qe.scheduler.dag.FragmentInstanceExecState;
import com.starrocks.system.ComputeNode;
import com.starrocks.task.LoadEtlTask;
import com.starrocks.thrift.FrontendServiceVersion;
import com.starrocks.thrift.TReportExecStatusParams;
import com.starrocks.thrift.TSinkCommitInfo;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TTabletCommitInfo;
import com.starrocks.thrift.TTabletFailInfo;
import mockit.Mock;
import mockit.MockUp;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class JoinTest extends SchedulerTestBase {

    @Test
    public void testCancelAtJoining() throws Exception {
        String sql = "insert into lineitem select * from lineitem";
        DefaultCoordinator scheduler = startScheduling(sql);

        AtomicBoolean joinFinished = new AtomicBoolean(false);
        Thread joinThread = new Thread(() -> joinFinished.set(scheduler.join(300)));
        joinThread.start();

        scheduler.cancel("Cancel by test");
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(joinFinished::get);

        Assertions.assertTrue(scheduler.isDone());
        Assertions.assertTrue(scheduler.checkBackendState());
        Assertions.assertTrue(scheduler.getExecStatus().isCancelled());
    }

    @Test
    public void testBackendBecomeDeadAtJoining() throws Exception {
        String sql = "insert into lineitem select * from lineitem";
        DefaultCoordinator scheduler = startScheduling(sql);

        AtomicBoolean joinFinished = new AtomicBoolean(false);
        Thread joinThread = new Thread(() -> joinFinished.set(scheduler.join(300)));
        joinThread.start();

        new MockUp<ComputeNode>() {
            @Mock
            public long getLastMissingHeartbeatTime() {
                return 10L;
            }
        };
        Awaitility.await().atMost(7, TimeUnit.SECONDS).until(joinFinished::get);

        Assertions.assertFalse(scheduler.isDone());
        Assertions.assertFalse(scheduler.checkBackendState());
        Assertions.assertEquals(TStatusCode.INTERNAL_ERROR, scheduler.getExecStatus().getErrorCode());
    }

    @Test
    public void testReportFailedExecutionAtJoining() throws Exception {
        String sql = "insert into lineitem select * from lineitem";
        DefaultCoordinator scheduler = startScheduling(sql);

        AtomicBoolean joinFinished = new AtomicBoolean(false);
        Thread joinThread = new Thread(() -> joinFinished.set(scheduler.join(300)));
        joinThread.start();

        scheduler.getExecutionDAG().getExecutionIndexesInJob().forEach(indexInJob -> {
            TReportExecStatusParams request = new TReportExecStatusParams(FrontendServiceVersion.V1);
            request.setBackend_num(indexInJob);
            request.setDone(true);
            request.setStatus(new TStatus(TStatusCode.INTERNAL_ERROR));

            scheduler.updateFragmentExecStatus(request);
        });

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(joinFinished::get);

        Assertions.assertTrue(scheduler.isDone());
        Assertions.assertEquals(TStatusCode.INTERNAL_ERROR, scheduler.getExecStatus().getErrorCode());
    }

    @Test
    public void testJoinFinishSuccessfully() throws Exception {
        String sql = "insert into lineitem select * from lineitem";
        DefaultCoordinator scheduler = startScheduling(sql);

        AtomicBoolean joinFinished = new AtomicBoolean(false);
        Thread joinThread = new Thread(() -> joinFinished.set(scheduler.join(300)));
        joinThread.start();

        // Receive non-EOS updateFragmentExecStatus for each Execution.
        scheduler.getExecutionDAG().getExecutionIndexesInJob().forEach(indexInJob -> {
            TReportExecStatusParams request = new TReportExecStatusParams(FrontendServiceVersion.V1);
            request.setBackend_num(indexInJob);
            request.setDone(false);
            request.setStatus(new TStatus(TStatusCode.OK));

            scheduler.updateFragmentExecStatus(request);
        });
        for (FragmentInstanceExecState execution : scheduler.getExecutionDAG().getExecutions()) {
            Assertions.assertFalse(execution.isFinished());
        }
        Assertions.assertFalse(joinFinished.get());

        // Receive duplicated EOS updateFragmentExecStatus for each Execution.
        final List<String> deltaUrls = Lists.newArrayList();
        final Map<String, Long> loadCounters = Maps.newHashMap();
        String trackingUrl = "track_url";
        final List<String> exportFiles = Lists.newArrayList();
        final List<TTabletCommitInfo> commitInfos = Lists.newArrayList();
        final List<TTabletFailInfo> failInfos = Lists.newArrayList();
        final Set<String> rejectedRecordPaths = Sets.newHashSet();
        final List<TSinkCommitInfo> sinkCommitInfos = Lists.newArrayList();
        for (int i = 0; i < 2; i++) {
            final int finalIndex = i;
            scheduler.getExecutionDAG().getExecutions().forEach(execution -> {
                int indexInJob = execution.getIndexInJob();

                TReportExecStatusParams request = new TReportExecStatusParams(FrontendServiceVersion.V1);

                request.setBackend_num(indexInJob)
                        .setDone(true)
                        .setStatus(new TStatus(TStatusCode.OK))
                        .setFragment_instance_id(execution.getInstanceId());

                String deltaUrl = "delta_url-" + indexInJob + "-" + finalIndex;
                request.setDelta_urls(Collections.singletonList(deltaUrl));

                request.setTracking_url(trackingUrl);

                String exportFile = "export_file-" + indexInJob + "-" + finalIndex;
                request.setExport_files(Collections.singletonList(exportFile));

                TTabletCommitInfo commitInfo = new TTabletCommitInfo(indexInJob, indexInJob);
                request.setCommitInfos(Collections.singletonList(commitInfo));

                TTabletFailInfo failInfo = new TTabletFailInfo();
                request.setFailInfos(Collections.singletonList(failInfo));

                String rejectedRecordPath = "rejectedRecordPath-" + indexInJob + "-" + finalIndex;
                request.setRejected_record_path(rejectedRecordPath);

                TSinkCommitInfo sinkCommitInfo = new TSinkCommitInfo();
                request.setSink_commit_infos(Collections.singletonList(sinkCommitInfo));

                Map<String, String> currLoadCounters = ImmutableMap.of(
                        LoadEtlTask.DPP_NORMAL_ALL, String.valueOf(finalIndex + 10),
                        LoadEtlTask.DPP_ABNORMAL_ALL, String.valueOf(finalIndex + 20),
                        LoadJob.UNSELECTED_ROWS, String.valueOf(finalIndex + 30),
                        LoadJob.LOADED_BYTES, String.valueOf(finalIndex + 40)
                );
                request.setLoad_counters(currLoadCounters);

                if (finalIndex == 0) {
                    deltaUrls.add(deltaUrl);
                    exportFiles.add(exportFile);
                    commitInfos.add(commitInfo);
                    failInfos.add(failInfo);
                    rejectedRecordPaths.add(execution.getAddress().getHostname() + ":" + rejectedRecordPath);
                    sinkCommitInfos.add(sinkCommitInfo);

                    currLoadCounters.forEach((key, value) -> {
                        long longValue = Long.parseLong(value);
                        loadCounters.compute(key, (k, v) -> v == null ? longValue : v + longValue);
                    });
                }

                scheduler.updateFragmentExecStatus(request);
            });
        }
        for (FragmentInstanceExecState execution : scheduler.getExecutionDAG().getExecutions()) {
            Assertions.assertTrue(execution.isFinished());
        }

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(joinFinished::get);

        Assertions.assertTrue(scheduler.isDone());
        Assertions.assertTrue(scheduler.getExecStatus().ok());

        // Check updateLoadInformation.
        Assertions.assertEquals(deltaUrls, scheduler.getDeltaUrls());
        Assertions.assertEquals(trackingUrl, scheduler.getTrackingUrl());
        Assertions.assertEquals(exportFiles, scheduler.getExportFiles());
        Assertions.assertEquals(commitInfos, scheduler.getCommitInfos());
        Assertions.assertEquals(failInfos, scheduler.getFailInfos());
        Assertions.assertEquals(new ArrayList<>(rejectedRecordPaths), scheduler.getRejectedRecordPaths());
        Assertions.assertEquals(sinkCommitInfos, scheduler.getSinkCommitInfos());

        Map<String, String> stringLoadCounters = loadCounters.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
        Assertions.assertEquals(stringLoadCounters, scheduler.getLoadCounters());
    }

    @Test
    public void testUpdateExecStatusConcurrently() throws Exception {
        String sql = "insert into lineitem select a.* from lineitem a join lineitem b on a.L_ORDERKEY=b.L_ORDERKEY";
        DefaultCoordinator scheduler = startScheduling(sql);
        List<Integer> executionIndexes = scheduler.getExecutionDAG().getExecutions().stream()
                .map(FragmentInstanceExecState::getIndexInJob)
                .collect(Collectors.toList());
        int fragmentInstanceCount = executionIndexes.size();
        // threads for each fragment instance to mock duplicated updateFragmentExecStatus and some of them are not EOS.
        final int threadCountPerInstance = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCountPerInstance * fragmentInstanceCount);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < fragmentInstanceCount * threadCountPerInstance; i++) {
            final int indexInJob = i % fragmentInstanceCount;
            final boolean isDone = i % 2 == 0;
            futures.add(executor.submit(() -> {
                TReportExecStatusParams request = new TReportExecStatusParams(FrontendServiceVersion.V1);
                request.setBackend_num(indexInJob);
                request.setDone(isDone);
                request.setStatus(new TStatus(TStatusCode.OK));
                scheduler.updateFragmentExecStatus(request);
            }));
        }
        // ensure finishInstance will not be called duplicate
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();
        for (int i = 0; i < fragmentInstanceCount; i++) {
            int indexInJob = executionIndexes.get(i);
            FragmentInstanceExecState execState = scheduler.getExecutionDAG().getExecution(indexInJob);
            System.out.println(execState.getState());
            Assertions.assertTrue(execState.getState() == FragmentInstanceExecState.State.FINISHED);
        }
    }
}
