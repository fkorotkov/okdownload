/*
 * Copyright (c) 2017 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.liulishuo.okdownload.core.download;

import android.net.Uri;

import com.liulishuo.okdownload.DownloadListener;
import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.OkDownload;
import com.liulishuo.okdownload.core.breakpoint.BlockInfo;
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo;
import com.liulishuo.okdownload.core.breakpoint.BreakpointStore;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.dispatcher.CallbackDispatcher;
import com.liulishuo.okdownload.core.dispatcher.DownloadDispatcher;
import com.liulishuo.okdownload.core.file.MultiPointOutputStream;
import com.liulishuo.okdownload.core.file.ProcessFileStrategy;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static com.liulishuo.okdownload.TestUtils.mockOkDownload;
import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class DownloadCallTest {

    private DownloadCall call;

    @Mock
    private DownloadTask mockTask;
    @Mock
    private BreakpointInfo mockInfo;

    @BeforeClass
    public static void setupClass() throws IOException {
        mockOkDownload();
    }

    @Before
    public void setup() throws InterruptedException {
        initMocks(this);
        when(mockTask.getUri()).thenReturn(mock(Uri.class));
        when(mockTask.getListener()).thenReturn(mock(DownloadListener.class));
        call = spy(DownloadCall.create(mockTask, false));

        final Future mockFuture = mock(Future.class);
        doReturn(mockFuture).when(call).submitChain(any(DownloadChain.class));
        when(mockFuture.isDone()).thenReturn(true);

        when(mockInfo.getBlockCount()).thenReturn(3);
        when(mockInfo.getTotalLength()).thenReturn(30L);
        when(mockInfo.getBlock(0)).thenReturn(new BlockInfo(0, 10));
        when(mockInfo.getBlock(1)).thenReturn(new BlockInfo(10, 10));
        when(mockInfo.getBlock(2)).thenReturn(new BlockInfo(20, 10));
    }


    @Test
    public void execute_createIfNon() throws IOException, InterruptedException {
        mockLocalCheck(true);

        final BreakpointStore mockStore = OkDownload.with().breakpointStore();

        when(mockStore.get(anyInt())).thenReturn(null);
        when(mockStore.createAndInsert(mockTask)).thenReturn(mockInfo);
        doNothing().when(call).start(any(DownloadCache.class), eq(mockInfo), anyBoolean());
        doNothing().when(call).startBlocks(any(List.class));

        call.execute();

        verify(mockStore).createAndInsert(mockTask);
    }

    @Test
    public void execute_blockComplete_ignore() throws InterruptedException {
        mockLocalCheck(true);
        when(mockInfo.getBlock(1)).thenReturn(new BlockInfo(10, 10, 10));
        doNothing().when(call).startBlocks(any(List.class));

        call.execute();

        ArgumentCaptor<List<DownloadChain>> captor = ArgumentCaptor.forClass(List.class);

        verify(call).startBlocks(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    public void execute_availableResume_startAllBlocks() throws InterruptedException {
        mockLocalCheck(true);
        doNothing().when(call).startBlocks(any(List.class));

        call.execute();

        ArgumentCaptor<List<DownloadChain>> captor = ArgumentCaptor.forClass(List.class);

        verify(call).startBlocks(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    public void execute_notAvailableResume_startFirstAndOthers() throws InterruptedException {
        mockLocalCheck(false);
        doNothing().when(call).startBlocks(any(List.class));

        call.execute();

        verify(call).submitChain(any(DownloadChain.class));
        ArgumentCaptor<List<DownloadChain>> captor = ArgumentCaptor.forClass(List.class);
        verify(call).startBlocks(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    public void execute_filenameOnStore_validFilename() throws InterruptedException {
        mockLocalCheck(true);
        doNothing().when(call).startBlocks(any(List.class));

        final String filename = "valid-filename";
        when(mockInfo.getFilename()).thenReturn(filename);

        call.execute();

        verify(OkDownload.with().downloadStrategy()).validFilenameFromResume(filename, mockTask);
    }

    @Test
    public void execute_preconditionFailed() throws InterruptedException {
        mockLocalCheck(true);
        doNothing().when(call).startBlocks(any(List.class));

        final DownloadCache mockCache = mock(DownloadCache.class);
        doReturn(mockCache).when(call).createCache(any(MultiPointOutputStream.class));
        when(mockCache.isPreconditionFailed()).thenReturn(true, false);
        final ResumeFailedCause resumeFailedCause = mock(ResumeFailedCause.class);
        doReturn(resumeFailedCause).when(mockCache).getResumeFailedCause();

        call.execute();

        verify(call).start(mockCache, mockInfo, true);
        verify(call).start(mockCache, mockInfo, false);

        // only callback on the first time
        final ProcessFileStrategy.ResumeAvailableLocalCheck mockLocalCheck = OkDownload.with()
                .processFileStrategy().resumeAvailableLocalCheck(mockTask, mockInfo);
        verify(mockLocalCheck).callbackCause();
        final CallbackDispatcher mockCallbackDispatcher = OkDownload.with().callbackDispatcher();
        verify(mockCallbackDispatcher.dispatch()).downloadFromBeginning(mockTask, mockInfo,
                resumeFailedCause);
    }

    @Test
    public void execute_preconditionFailedMaxTimes() throws InterruptedException, IOException {
        // re-mock for test static ref.
        mockOkDownload();
        mockLocalCheck(false);

        final DownloadCache mockCache = mock(DownloadCache.class);
        doReturn(mockCache).when(call).createCache(any(MultiPointOutputStream.class));
        when(mockCache.isPreconditionFailed()).thenReturn(true);
        doNothing().when(call).startBlocks(any(List.class));

        call.execute();

        verify(call, times(DownloadCall.MAX_COUNT_RETRY_FOR_PRECONDITION_FAILED + 1)).start(
                mockCache, mockInfo, false);

        // only once.
        verify(OkDownload.with().callbackDispatcher().dispatch()).taskStart(mockTask);
    }

    @Test
    public void execute_end() throws InterruptedException, IOException {
        // re-mock for test static ref.
        mockOkDownload();
        mockLocalCheck(false);

        final ProcessFileStrategy mockFileStrategy = OkDownload.with().processFileStrategy();
        final BreakpointStore mockStore = OkDownload.with().breakpointStore();
        final DownloadCache mockCache = mock(DownloadCache.class);
        doReturn(mockCache).when(call).createCache(any(MultiPointOutputStream.class));
        final IOException mockIOException = mock(IOException.class);
        when(mockCache.getRealCause()).thenReturn(mockIOException);
        doNothing().when(call).startBlocks(any(List.class));

        final DownloadListener mockListener = OkDownload.with().callbackDispatcher().dispatch();

        call.canceled = true;
        call.execute();
        verify(mockListener, never()).taskEnd(any(DownloadTask.class), any(EndCause.class),
                nullable(Exception.class));

        when(mockCache.getOutputStream()).thenReturn(mock(MultiPointOutputStream.class));
        call.canceled = false;
        call.execute();
        verify(mockListener).taskEnd(mockTask, EndCause.COMPLETED, null);
        verify(mockStore)
                .onTaskEnd(eq(mockTask.getId()), eq(EndCause.COMPLETED), nullable(Exception.class));
        verify(mockFileStrategy).completeProcessStream(any(MultiPointOutputStream.class),
                eq(mockTask));

        when(mockCache.isPreAllocateFailed()).thenReturn(true);
        call.execute();
        verify(mockListener).taskEnd(mockTask, EndCause.PRE_ALLOCATE_FAILED, mockIOException);

        when(mockCache.isFileBusyAfterRun()).thenReturn(true);
        call.execute();
        verify(mockListener).taskEnd(mockTask, EndCause.FILE_BUSY, null);

        when(mockCache.isServerCanceled()).thenReturn(true);
        call.execute();
        verify(mockListener).taskEnd(mockTask, EndCause.ERROR, mockIOException);


        when(mockCache.isUserCanceled()).thenReturn(false);
        when(mockCache.isServerCanceled()).thenReturn(false);
        when(mockCache.isUnknownError()).thenReturn(true);
        call.execute();
        verify(mockListener, times(2)).taskEnd(mockTask, EndCause.ERROR, mockIOException);
    }

    @Test
    public void finished_callToDispatch() {
        call.finished();

        verify(OkDownload.with().downloadDispatcher()).finish(call);
    }

    @Test
    public void compareTo() {
        final DownloadCall compareCall = mock(DownloadCall.class);
        when(compareCall.getPriority()).thenReturn(6);
        when(call.getPriority()).thenReturn(3);

        final int result = call.compareTo(compareCall);
        assertThat(result).isEqualTo(3);
    }

    @Test
    public void startBlocks() throws InterruptedException {
        ArrayList<DownloadChain> runningBlockList = spy(new ArrayList<DownloadChain>());
        call = spy(new DownloadCall(mockTask, false, runningBlockList));

        final Future mockFuture = mock(Future.class);
        doReturn(mockFuture).when(call).submitChain(any(DownloadChain.class));

        List<DownloadChain> chains = new ArrayList<>();
        chains.add(mock(DownloadChain.class));
        chains.add(mock(DownloadChain.class));
        chains.add(mock(DownloadChain.class));

        call.startBlocks(chains);

        verify(call, times(3)).submitChain(any(DownloadChain.class));
        verify(runningBlockList).addAll(eq(chains));
        verify(runningBlockList).removeAll(eq(chains));
    }

    @Test
    public void cancel() {
        assertThat(call.cancel()).isTrue();
        // canceled
        assertThat(call.cancel()).isFalse();
    }


    @Test
    public void cancel_finishing() {
        call.finishing = true;
        assertThat(call.cancel()).isFalse();
        final DownloadDispatcher dispatcher = OkDownload.with().downloadDispatcher();
        verify(dispatcher, never()).flyingCanceled(eq(call));
    }

    private void mockLocalCheck(boolean isAvailable) {
        final ProcessFileStrategy fileStrategy = OkDownload.with().processFileStrategy();
        ProcessFileStrategy.ResumeAvailableLocalCheck localCheck = mock(
                ProcessFileStrategy.ResumeAvailableLocalCheck.class);
        when(localCheck.isAvailable()).thenReturn(isAvailable);
        doReturn(localCheck).when(fileStrategy).resumeAvailableLocalCheck(any(DownloadTask.class),
                any(BreakpointInfo.class));


        final BreakpointStore mockStore = OkDownload.with().breakpointStore();
        when(mockStore.get(anyInt())).thenReturn(mockInfo);

        doNothing().when(call).parkForFirstConnection();
    }

}