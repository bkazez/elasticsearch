/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.repositories.hdfs;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import javax.security.auth.Subject;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.repositories.ESBlobStoreContainerTestCase;

@ThreadLeakFilters(filters = {HdfsClientThreadLeakFilter.class})
public class HdfsBlobStoreContainerTests extends ESBlobStoreContainerTestCase {

    @Override
    protected BlobStore newBlobStore() throws IOException {
        FileContext fileContext;
        try {
            fileContext = AccessController.doPrivileged((PrivilegedExceptionAction<FileContext>)
                () -> createContext(new URI("hdfs:///")));
        } catch (PrivilegedActionException e) {
            throw new RuntimeException(e.getCause());
        }
        return new HdfsBlobStore(fileContext, "temp", 1024);
    }

    @SuppressForbidden(reason = "lesser of two evils (the other being a bunch of JNI/classloader nightmares)")
    private FileContext createContext(URI uri) {
        // mirrors HdfsRepository.java behaviour
        Configuration cfg = new Configuration(true);
        cfg.setClassLoader(HdfsRepository.class.getClassLoader());
        cfg.reloadConfiguration();

        Constructor<?> ctor;
        Subject subject;

        try {
            Class<?> clazz = Class.forName("org.apache.hadoop.security.User");
            ctor = clazz.getConstructor(String.class);
            ctor.setAccessible(true);
        }  catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        try {
            Principal principal = (Principal) ctor.newInstance(System.getProperty("user.name"));
            subject = new Subject(false, Collections.singleton(principal),
                    Collections.emptySet(), Collections.emptySet());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        // disable file system cache
        cfg.setBoolean("fs.hdfs.impl.disable.cache", true);

        // set file system to TestingFs to avoid a bunch of security
        // checks, similar to what is done in HdfsTests.java
        cfg.set("fs.AbstractFileSystem." + uri.getScheme() + ".impl", TestingFs.class.getName());

        // create the FileContext with our user
        return Subject.doAs(subject, (PrivilegedAction<FileContext>) () -> {
            try {
                TestingFs fs = (TestingFs) AbstractFileSystem.get(uri, cfg);
                return FileContext.getFileContext(fs, cfg);
            } catch (UnsupportedFileSystemException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
