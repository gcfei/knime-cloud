/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   21.08.2019 (Mareike Hoeger, KNIME GmbH, Konstanz, Germany): created
 */
package org.knime.cloud.aws.filehandling.connections;

import java.io.IOException;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.knime.filehandling.core.connections.base.attributes.BaseFileAttributes;

import com.amazonaws.AbortedException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;

/**
 * S3PathIterator that iterates over the files and folders in a path.
 *
 * @author Mareike Hoeger, KNIME GmbH, Konstanz, Germany
 */
public class S3PathIterator implements Iterator<S3Path> {

    private final AmazonS3 m_client;

    private ListObjectsV2Result m_objectsListing;

    private List<S3ObjectSummary> m_objectSummary;

    private final S3FileSystem m_fileSystem;

    private S3Path m_nextPath;

    private final Filter<? super S3Path> m_filter;

    private List<String> m_objectsCommonPrefixes;

    private final String m_bucketName;

    private Iterator<S3Path> m_roots;

    private ListObjectsV2Request m_listRequest;

    /**
     * Creates an S3PathIterator that iterates over the files and folders of the given path.
     *
     * @param path the path to iterate over
     * @param filter the filter to use for the iteration
     */
    public S3PathIterator(final S3Path path, final Filter<? super S3Path> filter) {
        m_fileSystem = path.getFileSystem();
        m_client = m_fileSystem.getClient();
        m_filter = filter;
        m_bucketName = path.getBucketName();

        try {
            if (path.getNameCount() == 0) {
                final List<Bucket> buckets = m_client.listBuckets();
                final ArrayList<S3Path> rootPaths = new ArrayList<>();
                for (final Bucket thebucket : buckets) {

                    final S3Path bucketPath = m_fileSystem.getPath(m_fileSystem.getSeparator() + thebucket.getName(),
                        m_fileSystem.getSeparator());

                    final BaseFileAttributes attributes = S3FileSystemProvider.createBucketFileAttributes(thebucket, bucketPath);
                    m_fileSystem.addToAttributeCache(bucketPath, attributes);

                    rootPaths.add(bucketPath);
                }
                m_roots = rootPaths.iterator();
            } else {
                m_listRequest = new ListObjectsV2Request();
                m_listRequest.withBucketName(m_bucketName).withPrefix(path.getBlobName())
                    .withDelimiter(m_fileSystem.getSeparator()).withEncodingType("url")
                    .withStartAfter(path.getBlobName());
                m_objectsListing = m_client.listObjectsV2(m_listRequest);
                m_objectSummary = m_objectsListing.getObjectSummaries();
                m_objectsCommonPrefixes = m_objectsListing.getCommonPrefixes();
            }
        } catch (final SdkClientException e) {
            if ((e instanceof AbortedException) || (e.getCause() instanceof AbortedException)) {
                // ignore
            } else {
                throw e;
            }
        }

        m_nextPath = m_objectsListing == null && m_roots == null ? null : getNextPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return m_nextPath != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public S3Path next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        final S3Path path = m_nextPath;

        m_nextPath = getNextPath();

        return path;
    }

    private boolean checkAndFillSummaryList() {
        if (m_objectSummary.isEmpty() && m_objectsCommonPrefixes.isEmpty() && !m_objectsListing.isTruncated()) {
            return false;
        }

        if (m_objectSummary.isEmpty() && m_objectsListing.isTruncated()) {

            m_listRequest.setContinuationToken(m_objectsListing.getNextContinuationToken());

            m_objectsListing = m_client.listObjectsV2(m_listRequest);
            m_objectSummary = m_objectsListing.getObjectSummaries();
            m_objectsCommonPrefixes.addAll(m_objectsListing.getCommonPrefixes());
        }

        return true;
    }

    private S3Path getNextPath() {

        if (m_roots != null) {

            if (m_roots.hasNext()) {
                return m_roots.next();
            }

        } else {

            while (checkAndFillSummaryList()) {
                S3Path path = null;
                if (!m_objectSummary.isEmpty()) {
                    path = getPathFromSummary(m_objectSummary.remove(0));
                } else {
                    path = getPathFromPrefix(m_objectsCommonPrefixes.remove(0));
                }

                try {
                    if (m_filter.accept(path)) {
                        return path;
                    }
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        return null;
    }

    private S3Path getPathFromPrefix(final String commonPrefix) {
        final S3Path path = new S3Path(m_fileSystem, m_bucketName, commonPrefix);
        final FileTime lastModified = FileTime.fromMillis(0L);
        final BaseFileAttributes attributes = new BaseFileAttributes(false, path, lastModified, lastModified,
            lastModified, 0, false, false, new S3PosixAttributesFetcher());
        m_fileSystem.addToAttributeCache(path, attributes);
        return path;
    }

    private S3Path getPathFromSummary(final S3ObjectSummary nextSummary) {
        final S3Path path = new S3Path(m_fileSystem, nextSummary.getBucketName(), nextSummary.getKey());
        final FileTime lastModified = FileTime.from(nextSummary.getLastModified().toInstant());

        final BaseFileAttributes attributes = new BaseFileAttributes(!path.isDirectory(), path, lastModified,
            lastModified, lastModified, nextSummary.getSize(), false, false, new S3PosixAttributesFetcher());
        m_fileSystem.addToAttributeCache(path, attributes);

        return path;
    }

}
