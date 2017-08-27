/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.source.slf4jtaildir;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.annotations.InterfaceAudience;
import org.apache.flume.annotations.InterfaceStability;
import org.apache.flume.client.avro.ReliableEventReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.gson.stream.JsonReader;

@InterfaceAudience.Private
@InterfaceStability.Evolving
public class ReliableSlf4jTaildirEventReader implements ReliableEventReader {
    private static final Logger                 logger      = LoggerFactory
        .getLogger(ReliableSlf4jTaildirEventReader.class);

    private final List<Slf4jTaildirMatcher>     taildirCache;
    private final Table<String, String, String> headerTable;

    private Slf4jTailFile                       currentFile = null;
    private Map<Long, Slf4jTailFile>            tailFiles   = Maps.newHashMap();
    private long                                updateTime;
    private boolean                             addByteOffset;
    private boolean                             cachePatternMatching;
    private boolean                             committed   = true;
    private final boolean                       annotateFileName;
    private final String                        fileNameHeader;

    /**
     * Create a ReliableTaildirEventReader to watch the given directory.
     */
    private ReliableSlf4jTaildirEventReader(Map<String, String> filePaths,
                                            Table<String, String, String> headerTable,
                                            String positionFilePath, boolean skipToEnd,
                                            boolean addByteOffset, boolean cachePatternMatching,
                                            boolean annotateFileName,
                                            String fileNameHeader) throws IOException {
        // Sanity checks
        Preconditions.checkNotNull(filePaths);
        Preconditions.checkNotNull(positionFilePath);

        if (logger.isDebugEnabled()) {
            logger.debug("Initializing {} with directory={}, metaDir={}",
                new Object[] { ReliableSlf4jTaildirEventReader.class.getSimpleName(), filePaths });
        }

        List<Slf4jTaildirMatcher> taildirCache = Lists.newArrayList();
        for (Entry<String, String> e : filePaths.entrySet()) {
            taildirCache
                .add(new Slf4jTaildirMatcher(e.getKey(), e.getValue(), cachePatternMatching));
        }
        logger.info("taildirCache: " + taildirCache.toString());
        logger.info("headerTable: " + headerTable.toString());

        this.taildirCache = taildirCache;
        this.headerTable = headerTable;
        this.addByteOffset = addByteOffset;
        this.cachePatternMatching = cachePatternMatching;
        this.annotateFileName = annotateFileName;
        this.fileNameHeader = fileNameHeader;
        updateTailFiles(skipToEnd);

        logger.info("Updating position from position file: " + positionFilePath);
        loadPositionFile(positionFilePath);
    }

    /**
     * Load a position file which has the last read position of each file.
     * If the position file exists, update tailFiles mapping.
     */
    public void loadPositionFile(String filePath) {
        Long inode, pos;
        String path;
        FileReader fr = null;
        JsonReader jr = null;
        try {
            fr = new FileReader(filePath);
            jr = new JsonReader(fr);
            jr.beginArray();
            while (jr.hasNext()) {
                inode = null;
                pos = null;
                path = null;
                jr.beginObject();
                while (jr.hasNext()) {
                    switch (jr.nextName()) {
                        case "inode":
                            inode = jr.nextLong();
                            break;
                        case "pos":
                            pos = jr.nextLong();
                            break;
                        case "file":
                            path = jr.nextString();
                            break;
                    }
                }
                jr.endObject();

                for (Object v : Arrays.asList(inode, pos, path)) {
                    Preconditions.checkNotNull(v,
                        "Detected missing value in position file. " + "inode: " + inode + ", pos: "
                                                  + pos + ", path: " + path);
                }
                Slf4jTailFile tf = tailFiles.get(inode);
                if (tf != null && tf.updatePos(path, inode, pos)) {
                    tailFiles.put(inode, tf);
                } else {
                    //position文件里面的东西只是用来更新pos的，具体这个tailFiles里面有哪些，是根据taildirMatcher 来获取的。
                    logger.info("Missing file: " + path + ", inode: " + inode + ", pos: " + pos);
                }
            }
            jr.endArray();
        } catch (FileNotFoundException e) {
            logger.info("File not found: " + filePath + ", not updating position");
        } catch (IOException e) {
            logger.error("Failed loading positionFile: " + filePath, e);
        } finally {
            try {
                if (fr != null)
                    fr.close();
                if (jr != null)
                    jr.close();
            } catch (IOException e) {
                logger.error("Error: " + e.getMessage(), e);
            }
        }
    }

    public Map<Long, Slf4jTailFile> getTailFiles() {
        return tailFiles;
    }

    public void setCurrentFile(Slf4jTailFile currentFile) {
        this.currentFile = currentFile;
    }

    @Override
    public Event readEvent() throws IOException {
        List<Event> events = readEvents(1);
        if (events.isEmpty()) {
            return null;
        }
        return events.get(0);
    }

    @Override
    public List<Event> readEvents(int numEvents) throws IOException {
        return readEvents(numEvents, false);
    }

    @VisibleForTesting
    public List<Event> readEvents(Slf4jTailFile tf, int numEvents) throws IOException {
        setCurrentFile(tf);
        return readEvents(numEvents, true);
    }

    public List<Event> readEvents(int numEvents, boolean backoffWithoutNL) throws IOException {
        if (!committed) {
            if (currentFile == null) {
                throw new IllegalStateException(
                    "current file does not exist. " + currentFile.getPath());
            }
            logger.info("Last read was never committed - resetting position");
            long lastPos = currentFile.getPos();
            currentFile.updateFilePos(lastPos);
        }
        List<Event> events = currentFile.readEvents(numEvents, backoffWithoutNL, addByteOffset);
        if (events.isEmpty()) {
            return events;
        }

        Map<String, String> headers = currentFile.getHeaders();
        if (annotateFileName || (headers != null && !headers.isEmpty())) {
            for (Event event : events) {
                if (headers != null && !headers.isEmpty()) {
                    event.getHeaders().putAll(headers);
                }
                if (annotateFileName) {
                    event.getHeaders().put(fileNameHeader, currentFile.getPath());
                    event.getHeaders().put("basename", currentFile.getFileName());
                }
            }
        }
        committed = false;
        return events;
    }

    @Override
    public void close() throws IOException {
        for (Slf4jTailFile tf : tailFiles.values()) {
            if (tf.getRaf() != null)
                tf.getRaf().close();
        }
    }

    /** Commit the last lines which were read. */
    @Override
    public void commit() throws IOException {
        if (!committed && currentFile != null) {
            long pos = currentFile.getLineReadPos();
            currentFile.setPos(pos);
            currentFile.setLastUpdated(updateTime);
            committed = true;
        }
    }

    /**
     * Update tailFiles mapping if a new file is created or appends are detected
     * to the existing file.
     */
    public List<Long> updateTailFiles(boolean skipToEnd) throws IOException {
        updateTime = System.currentTimeMillis();
        List<Long> updatedInodes = Lists.newArrayList();
        logger.info("start to updateTailFiles, now ");
        Set<Long> usedNodes = new HashSet<Long>();
        for (Slf4jTaildirMatcher taildir : taildirCache) {
            Map<String, String> headers = headerTable.row(taildir.getFileGroup());
            for (File f : taildir.getMatchingFiles()) {
                long inode = getInode(f);
                Slf4jTailFile tf = tailFiles.get(inode);
                //获取了一个新文件，key是inode
                //改成startWith，是因为符合log4j 增加 日期后缀的习惯
                //                logger.info("qrjdebug: now to process f with: " + f.getAbsolutePath());
                if (tf == null || !f.getAbsolutePath().startsWith(tf.getPath())) {
                    //                    logger.info("qrjdebug2: now file: " + f.getAbsolutePath());
                    long startPos = skipToEnd ? f.length() : 0;
                    tf = openFile(f, headers, inode, startPos);
                } else {
                    //                    logger.info("qrjdebug2: get the startWith file: " + tf.getPath() + " , "
                    //                                + f.getAbsolutePath());
                    //为了支持log4j, 特意加上的~.
                    tf.setPath(f.getAbsolutePath());
                    boolean updated = tf.getLastUpdated() < f.lastModified();
                    if (updated) {
                        if (tf.getRaf() == null) {
                            tf = openFile(f, headers, inode, tf.getPos());
                        }
                        if (f.length() < tf.getPos()) {
                            logger.info("Pos " + tf.getPos() + " is larger than file size! "
                                        + "Restarting from pos 0, file: " + tf.getPath()
                                        + ", inode: " + inode);
                            tf.updatePos(tf.getPath(), inode, 0);
                        }
                    }
                    tf.setNeedTail(updated);
                }
                tailFiles.put(inode, tf);
                usedNodes.add(inode);
                updatedInodes.add(inode);
            }
        }
        //获取这次不用的node,看文件是不是已经删除了，如果是的话，就删除掉内存里面的key,也能减少position file 的大小
        Iterator<Entry<Long, Slf4jTailFile>> iter = tailFiles.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<Long, Slf4jTailFile> entry = iter.next();
            if (!usedNodes.contains(entry.getKey())) {
                iter.remove();
                entry.getValue().close();
            }
        }
        return updatedInodes;
    }

    public List<Long> updateTailFiles() throws IOException {
        return updateTailFiles(false);
    }

    private long getInode(File file) throws IOException {
        long inode = (long) Files.getAttribute(file.toPath(), "unix:ino");
        return inode;
    }

    private Slf4jTailFile openFile(File file, Map<String, String> headers, long inode, long pos) {
        try {
            logger.info("Opening file: " + file + ", inode: " + inode + ", pos: " + pos);
            return new Slf4jTailFile(file, headers, inode, pos);
        } catch (IOException e) {
            throw new FlumeException("Failed opening file: " + file, e);
        }
    }

    /**
     * Special builder class for ReliableTaildirEventReader
     */
    public static class Builder {
        private Map<String, String>           filePaths;
        private Table<String, String, String> headerTable;
        private String                        positionFilePath;
        private boolean                       skipToEnd;
        private boolean                       addByteOffset;
        private boolean                       cachePatternMatching;
        private Boolean                       annotateFileName = Slf4jTaildirSourceConfigurationConstants.DEFAULT_FILE_HEADER;
        private String                        fileNameHeader   = Slf4jTaildirSourceConfigurationConstants.DEFAULT_FILENAME_HEADER_KEY;

        public Builder filePaths(Map<String, String> filePaths) {
            this.filePaths = filePaths;
            return this;
        }

        public Builder headerTable(Table<String, String, String> headerTable) {
            this.headerTable = headerTable;
            return this;
        }

        public Builder positionFilePath(String positionFilePath) {
            this.positionFilePath = positionFilePath;
            return this;
        }

        public Builder skipToEnd(boolean skipToEnd) {
            this.skipToEnd = skipToEnd;
            return this;
        }

        public Builder addByteOffset(boolean addByteOffset) {
            this.addByteOffset = addByteOffset;
            return this;
        }

        public Builder cachePatternMatching(boolean cachePatternMatching) {
            this.cachePatternMatching = cachePatternMatching;
            return this;
        }

        public Builder annotateFileName(boolean annotateFileName) {
            this.annotateFileName = annotateFileName;
            return this;
        }

        public Builder fileNameHeader(String fileNameHeader) {
            this.fileNameHeader = fileNameHeader;
            return this;
        }

        public ReliableSlf4jTaildirEventReader build() throws IOException {
            return new ReliableSlf4jTaildirEventReader(filePaths, headerTable, positionFilePath,
                skipToEnd, addByteOffset, cachePatternMatching, annotateFileName, fileNameHeader);
        }
    }

}
