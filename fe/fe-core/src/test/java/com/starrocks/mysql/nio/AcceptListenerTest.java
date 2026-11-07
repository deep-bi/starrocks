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

package com.starrocks.mysql.nio;

import com.starrocks.common.ErrorCode;
import com.starrocks.common.Pair;
import com.starrocks.common.util.LogUtil;
import com.starrocks.mysql.MysqlProto;
import com.starrocks.mysql.NegotiateState;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ConnectScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.xnio.StreamConnection;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;

import java.net.InetSocketAddress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AcceptListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testRoleConnectionLimitRejectsLoginAfterAuthentication() throws Exception {
        ConnectScheduler scheduler = mock(ConnectScheduler.class);
        AcceptingChannel<StreamConnection> channel = mock(AcceptingChannel.class);
        StreamConnection connection = mock(StreamConnection.class);
        XnioWorker worker = mock(XnioWorker.class);
        when(channel.accept()).thenReturn(connection);
        when(channel.getWorker()).thenReturn(worker);
        when(connection.getPeerAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 9030));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(worker).execute(any(Runnable.class));
        when(scheduler.getNextConnectionId()).thenReturn(1);
        when(scheduler.registerConnection(any())).thenReturn(new Pair<>(true, null));
        String errMsg = ErrorCode.ERR_GROUP_MAX_CONNECTION.formatErrorMsg("r1", 1, 1);
        when(scheduler.registerRoleConnection(any())).thenReturn(new Pair<>(false, errMsg));

        MysqlProto.NegotiateResult ok = new MysqlProto.NegotiateResult(null, NegotiateState.OK);
        try (MockedStatic<MysqlProto> mysqlProto = mockStatic(MysqlProto.class);
                MockedStatic<LogUtil> ignored = mockStatic(LogUtil.class)) {
            mysqlProto.when(() -> MysqlProto.negotiate(any())).thenReturn(ok);
            mysqlProto.when(() -> MysqlProto.authenticate(any(), any())).thenReturn(ok);

            new AcceptListener(scheduler).handleEvent(channel);

            // the roles are counted only after authentication, and the client gets the rejection, not the OK packet
            ArgumentCaptor<ConnectContext> context = ArgumentCaptor.forClass(ConnectContext.class);
            verify(scheduler).registerRoleConnection(context.capture());
            mysqlProto.verify(() -> MysqlProto.sendResponsePacket(any()), times(1));
            Assertions.assertEquals(ErrorCode.ERR_GROUP_MAX_CONNECTION, context.getValue().getState().getErrorCode());
            Assertions.assertEquals(errMsg, context.getValue().getState().getErrorMessage());
        }
    }
}
