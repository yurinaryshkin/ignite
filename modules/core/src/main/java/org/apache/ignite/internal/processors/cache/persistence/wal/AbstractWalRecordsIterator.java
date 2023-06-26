/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.wal;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteOrder;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.internal.pagemem.wal.record.WALRecord;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.persistence.file.FileIO;
import org.apache.ignite.internal.processors.cache.persistence.file.FileIOFactory;
import org.apache.ignite.internal.processors.cache.persistence.wal.io.FileInput;
import org.apache.ignite.internal.processors.cache.persistence.wal.io.SegmentFileInputFactory;
import org.apache.ignite.internal.processors.cache.persistence.wal.io.SegmentIO;
import org.apache.ignite.internal.processors.cache.persistence.wal.serializer.RecordSerializer;
import org.apache.ignite.internal.processors.cache.persistence.wal.serializer.RecordSerializerFactory;
import org.apache.ignite.internal.processors.cache.persistence.wal.serializer.SegmentHeader;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.typedef.P2;
import org.apache.ignite.lang.IgniteBiTuple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.processors.cache.persistence.wal.serializer.RecordV1Serializer.readSegmentHeader;

/**
 * Iterator over WAL segments. This abstract class provides most functionality for reading records in log. Subclasses
 * are to override segment switching functionality
 */
public abstract class AbstractWalRecordsIterator extends ParentAbstractWalRecordsIterator {
    /** */
    private static final long serialVersionUID = 0L;

    /**
     * Current WAL segment absolute index. <br> Determined as lowest number of file at start, is changed during advance
     * segment
     */
    protected long curWalSegmIdx = -1;

    /**
     * Shared context for creating serializer of required version and grid name access. Also cacheObjects processor from
     * this context may be used to covert Data entry key and value from its binary representation into objects.
     */
    @NotNull protected final transient GridCacheSharedContext<?, ?> sharedCtx;

    /** Serializer factory. */
    @NotNull private final transient RecordSerializerFactory serializerFactory;

    /** Factory to provide I/O interfaces for read/write operations with files */
    @NotNull protected final transient FileIOFactory ioFactory;

    /** Utility buffer for reading records */
    private final transient ByteBufferExpander buf;

    /** Factory to provide I/O interfaces for read primitives with files. */
    private final transient SegmentFileInputFactory segmentFileInputFactory;
    
    /**
     * Current WAL segment read file handle. To be filled by subclass advanceSegment
     */
    private transient AbstractReadFileHandle currWalSegment;

    /**
     * @param log Logger.
     * @param sharedCtx Shared context.
     * @param serializerFactory Serializer of current version to read headers.
     * @param ioFactory ioFactory for file IO access.
     * @param initialReadBufferSize buffer for reading records size.
     * @param segmentFileInputFactory Factory to provide I/O interfaces for read primitives with files.
     */
    protected AbstractWalRecordsIterator(
        @NotNull final IgniteLogger log,
        @NotNull final GridCacheSharedContext<?, ?> sharedCtx,
        @NotNull final RecordSerializerFactory serializerFactory,
        @NotNull final FileIOFactory ioFactory,
        final int initialReadBufferSize,
        SegmentFileInputFactory segmentFileInputFactory) {
        super(log);
        this.sharedCtx = sharedCtx;
        this.serializerFactory = serializerFactory;
        this.ioFactory = ioFactory;
        this.segmentFileInputFactory = segmentFileInputFactory;

        buf = new ByteBufferExpander(initialReadBufferSize, ByteOrder.nativeOrder());
    }

    /** {@inheritDoc} */
    @Override protected void onClose() throws IgniteCheckedException {
        try {
            buf.close();
        }
        catch (Exception ex) {
            throw new IgniteCheckedException(ex);
        }
    }

    /**
         * @param tailReachedException Tail reached exception.
         * @param currWalSegment Current WAL segment read handler.
         * @return If need to throw exception after validation.
         */
    protected IgniteCheckedException validateTailReachedException(
        WalSegmentTailReachedException tailReachedException,
        AbstractReadFileHandle currWalSegment
    ) {
        if (!currWalSegment.workDir())
            return new IgniteCheckedException(
                "WAL tail reached in archive directory, " +
                    "WAL segment file is corrupted.",
                tailReachedException);
        return null;

    }

    /**
         * Switches records iterator to the next WAL segment as result of this method, new reference to segment should be
         * returned. Null for current handle means stop of iteration.
         *
         * @param curWalSegment current open WAL segment or null if there is no open segment yet
         * @return new WAL segment to read or null for stop iteration
         * @throws IgniteCheckedException if reading failed
         */
    protected abstract AbstractReadFileHandle advanceSegment(
        @Nullable final AbstractWalRecordsIterator.AbstractReadFileHandle curWalSegment
    ) throws IgniteCheckedException;

    /**
     * Assumes fileIO will be closed in this method in case of error occurred.
     *
     * @param desc File descriptor.
     * @param start Optional start pointer. Null means read from the beginning.
     * @param fileIO fileIO associated with file descriptor
     * @param segmentHeader read segment header from fileIO
     * @return Initialized file read header.
     * @throws IgniteCheckedException If initialized failed due to another unexpected error.
     */
    protected AbstractReadFileHandle initReadHandle(
        @NotNull final AbstractFileDescriptor desc,
        @Nullable final WALPointer start,
        @NotNull final SegmentIO fileIO,
        @NotNull final SegmentHeader segmentHeader
    ) throws IgniteCheckedException {
        try {
            boolean isCompacted = segmentHeader.isCompacted();

            if (isCompacted)
                serializerFactory.skipPositionCheck(true);

            FileInput in = segmentFileInputFactory.createFileInput(fileIO, buf);

            if (start != null && desc.idx() == start.index()) {
                if (isCompacted) {
                    if (start.fileOffset() != 0)
                        serializerFactory.recordDeserializeFilter(new StartSeekingFilter(start));
                }
                else {
                    // Make sure we skip header with serializer version.
                    long startOff = Math.max(start.fileOffset(), fileIO.position());

                    in.seek(startOff);
                }
            }

            int serVer = segmentHeader.getSerializerVersion();

            return createReadFileHandle(fileIO, serializerFactory.createSerializer(serVer), in);
        }
        catch (SegmentEofException | EOFException ignore) {
            closeFieIO(fileIO);

            return null;
        }
        catch (IgniteCheckedException e) {
            IgniteUtils.closeWithSuppressingException(fileIO, e);

            throw e;
        }
        catch (IOException e) {
            IgniteUtils.closeWithSuppressingException(fileIO, e);

            throw new IgniteCheckedException(
                "Failed to initialize WAL segment after reading segment header: " + desc.file().getAbsolutePath(), e);
        }
    }

    /**
     * Assumes file descriptor will be opened in this method. The caller of this method must be responsible for closing
     * opened file descriptor File descriptor will be closed ONLY in case of error occurred.
     *
     * @param desc File descriptor.
     * @param start Optional start pointer. Null means read from the beginning
     * @return Initialized file read header.
     * @throws FileNotFoundException If segment file is missing.
     * @throws IgniteCheckedException If initialized failed due to another unexpected error.
     */
    protected AbstractReadFileHandle initReadHandle(
        @NotNull final AbstractFileDescriptor desc,
        @Nullable final WALPointer start
    ) throws IgniteCheckedException, FileNotFoundException {
        SegmentIO fileIO = null;

        try {
            fileIO = desc.toReadOnlyIO(ioFactory);

            SegmentHeader segmentHeader = initHeader(fileIO);
            if (segmentHeader == null)
                return null;

            return initReadHandle(desc, start, fileIO, segmentHeader);
        }
        catch (FileNotFoundException e) {
            IgniteUtils.closeQuiet(fileIO);

            throw e;
        }
        catch (IOException e) {
            IgniteUtils.closeQuiet(fileIO);

            throw new IgniteCheckedException(
                "Failed to initialize WAL segment: " + desc.file().getAbsolutePath(), e);
        }
    }

    /** */
    @Nullable private SegmentHeader initHeader(SegmentIO fileIO) throws IgniteCheckedException, IOException {
        SegmentHeader segmentHeader;

        try {
            segmentHeader = readSegmentHeader(fileIO, segmentFileInputFactory);
        }
        catch (SegmentEofException | EOFException ignore) {
            closeFieIO(fileIO);

            return null;
        }
        catch (IOException | IgniteCheckedException e) {
            IgniteUtils.closeWithSuppressingException(fileIO, e);

            throw e;
        }
        return segmentHeader;
    }

    /** */
    private void closeFieIO(SegmentIO fileIO) throws IgniteCheckedException {
        try {
            fileIO.close();
        }
        catch (IOException ce) {
            throw new IgniteCheckedException(ce);
        }
    }

    /** */
    protected abstract AbstractReadFileHandle createReadFileHandle(
        SegmentIO fileIO,
        RecordSerializer ser,
        FileInput in
    );

    /**
     * Switches records iterator to the next record. <ul> <li>{@link #curRec} will be updated.</li> <li> If end of
     * segment reached, switch to new segment is called. {@link #currWalSegment} will be updated.</li> </ul>
     *
     * {@code advance()} runs a step ahead {@link #next()}
     *
     * @throws IgniteCheckedException If failed.
     */
    @Override protected void advance() throws IgniteCheckedException {
        if (curRec != null)
            lastRead = curRec.get1();

        while (true) {
            try {
                curRec = advanceRecord(currWalSegment);

                if (curRec != null) {
                    if (curRec.get2().type() == null) {
                        lastRead = curRec.get1();

                        continue; // Record was skipped by filter of current serializer, should read next record.
                    }

                    return;
                }
                else {
                    currWalSegment = advanceSegment(currWalSegment);

                    if (currWalSegment == null)
                        return;
                }
            }
            catch (WalSegmentTailReachedException e) {

                IgniteCheckedException e0 = validateTailReachedException(e, currWalSegment);

                if (e0 != null)
                    throw e0;

                log.warning(e.getMessage());

                curRec = null;

                return;
            }
        }
    }

    /**
     * Closes and returns WAL segment (if any)
     *
     * @return closed handle
     * @throws IgniteCheckedException if IO failed
     */
    @Nullable protected AbstractWalRecordsIterator.AbstractReadFileHandle closeCurrentWalSegment() throws IgniteCheckedException {
        final AbstractReadFileHandle walSegmentClosed = currWalSegment;

        if (walSegmentClosed != null) {
            walSegmentClosed.close();
            currWalSegment = null;
        }
        return walSegmentClosed;
    }

    /**
     * Switches to new record.
     *
     * @param hnd currently opened read handle.
     * @return next advanced record.
     */
    protected IgniteBiTuple<WALPointer, WALRecord> advanceRecord(
        @Nullable final AbstractWalRecordsIterator.AbstractReadFileHandle hnd
    ) throws IgniteCheckedException {
        if (hnd == null)
            return null;

        WALPointer actualFilePtr = new WALPointer(hnd.idx(), (int)hnd.in().position(), 0);

        try {
            WALRecord rec = hnd.ser().readRecord(hnd.in(), actualFilePtr);

            actualFilePtr.length(rec.size());

            // cast using diamond operator here can break compile for 7
            return new IgniteBiTuple<>(actualFilePtr, postProcessRecord(rec));
        }
        catch (IOException | IgniteCheckedException e) {
            if (e instanceof WalSegmentTailReachedException) {
                throw new WalSegmentTailReachedException(
                    "WAL segment tail reached. [idx=" + hnd.idx() +
                        ", isWorkDir=" + hnd.workDir() + ", serVer=" + hnd.ser() +
                        ", actualFilePtr=" + actualFilePtr + ']',
                    e
                );
            }

            if (!(e instanceof SegmentEofException) && !(e instanceof EOFException)) {
                IgniteCheckedException e0 = handleRecordException(e, actualFilePtr);

                if (e0 != null)
                    throw e0;
            }

            return null;
        }
    }

    /**
     * Performs final conversions with record loaded from WAL. To be overridden by subclasses if any processing
     * required.
     *
     * @param rec record to post process.
     * @return post processed record.
     */
    @NotNull protected WALRecord postProcessRecord(@NotNull final WALRecord rec) {
        return rec;
    }

    /**
     * Handler for record deserialization exception.
     *
     * @param e problem from records reading
     * @param ptr file pointer was accessed
     * @return {@code null} if the error was handled and we can go ahead, {@code IgniteCheckedException} if the error
     * was not handled, and we should stop the iteration.
     */
    protected IgniteCheckedException handleRecordException(
        @NotNull final Exception e,
        @Nullable final WALPointer ptr
    ) {
        if (log.isInfoEnabled())
            log.info("Stopping WAL iteration due to an exception: " + e.getMessage() + ", ptr=" + ptr);

        return new IgniteCheckedException(e);
    }

    /** */
    protected interface AbstractReadFileHandle {
        /** */
        void close() throws IgniteCheckedException;

        /** */
        long idx();

        /** */
        abstract FileInput in();

        /** */
        RecordSerializer ser();

        /** */
        boolean workDir();
    }

    /**
     * Filter that drops all records until given start pointer is reached.
     */
    private static class StartSeekingFilter implements P2<WALRecord.RecordType, WALPointer> {
        /** Serial version uid. */
        private static final long serialVersionUID = 0L;

        /** Start pointer. */
        private final WALPointer start;

        /** Start reached flag. */
        private boolean startReached;

        /**
         * @param start Start.
         */
        StartSeekingFilter(WALPointer start) {
            this.start = start;
        }

        /** {@inheritDoc} */
        @Override public boolean apply(WALRecord.RecordType type, WALPointer pointer) {
            if (start.fileOffset() == pointer.fileOffset())
                startReached = true;

            return startReached;
        }
    }

    /** */
    protected interface AbstractFileDescriptor {
        /** */
        boolean isCompressed();

        /** */
        File file();

        /** */
        long idx();

        /**
         * Make fileIo by this description.
         *
         * @param fileIOFactory Factory for fileIo creation.
         * @return One of implementation of {@link FileIO}.
         * @throws IOException if creation of fileIo was not success.
         */
        SegmentIO toReadOnlyIO(FileIOFactory fileIOFactory) throws IOException;
    }
}
