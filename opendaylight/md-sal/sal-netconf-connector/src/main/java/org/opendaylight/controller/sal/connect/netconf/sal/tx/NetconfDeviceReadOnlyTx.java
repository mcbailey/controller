/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.opendaylight.controller.sal.connect.netconf.sal.tx;

import static org.opendaylight.controller.sal.connect.netconf.util.NetconfMessageTransformUtil.CONFIG_SOURCE_RUNNING;
import static org.opendaylight.controller.sal.connect.netconf.util.NetconfMessageTransformUtil.NETCONF_DATA_QNAME;
import static org.opendaylight.controller.sal.connect.netconf.util.NetconfMessageTransformUtil.NETCONF_GET_CONFIG_QNAME;
import static org.opendaylight.controller.sal.connect.netconf.util.NetconfMessageTransformUtil.NETCONF_GET_QNAME;
import static org.opendaylight.controller.sal.connect.netconf.util.NetconfMessageTransformUtil.toFilterStructure;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.impl.util.compat.DataNormalizationException;
import org.opendaylight.controller.md.sal.common.impl.util.compat.DataNormalizer;
import org.opendaylight.controller.md.sal.dom.api.DOMDataReadOnlyTransaction;
import org.opendaylight.controller.sal.connect.netconf.util.NetconfMessageTransformUtil;
import org.opendaylight.controller.sal.connect.util.RemoteDeviceId;
import org.opendaylight.controller.sal.core.api.RpcImplementation;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.data.api.CompositeNode;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.Node;
import org.opendaylight.yangtools.yang.data.api.SimpleNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NetconfDeviceReadOnlyTx implements DOMDataReadOnlyTransaction {

    private static final Logger LOG  = LoggerFactory.getLogger(NetconfDeviceReadOnlyTx.class);

    private final RpcImplementation rpc;
    private final DataNormalizer normalizer;
    private final RemoteDeviceId id;

    public NetconfDeviceReadOnlyTx(final RpcImplementation rpc, final DataNormalizer normalizer, final RemoteDeviceId id) {
        this.rpc = rpc;
        this.normalizer = normalizer;
        this.id = id;
    }

    public ListenableFuture<Optional<NormalizedNode<?, ?>>> readConfigurationData(final YangInstanceIdentifier path) {
        final ListenableFuture<RpcResult<CompositeNode>> future = rpc.invokeRpc(NETCONF_GET_CONFIG_QNAME,
                NetconfMessageTransformUtil.wrap(NETCONF_GET_CONFIG_QNAME, CONFIG_SOURCE_RUNNING, toFilterStructure(path)));

        return Futures.transform(future, new Function<RpcResult<CompositeNode>, Optional<NormalizedNode<?, ?>>>() {
            @Override
            public Optional<NormalizedNode<?, ?>> apply(final RpcResult<CompositeNode> result) {
                checkReadSuccess(result, path);

                final CompositeNode data = result.getResult().getFirstCompositeByName(NETCONF_DATA_QNAME);
                final CompositeNode node = (CompositeNode) findNode(data, path);

                return data == null ?
                        Optional.<NormalizedNode<?, ?>>absent() :
                        transform(path, node);
            }
        });
    }

    private void checkReadSuccess(final RpcResult<CompositeNode> result, final YangInstanceIdentifier path) {
        LOG.warn("{}: Unable to read data: {}, errors: {}", id, path, result.getErrors());
        Preconditions.checkArgument(result.isSuccessful(), "%s: Unable to read data: %s, errors: %s", id, path, result.getErrors());
    }

    private Optional<NormalizedNode<?, ?>> transform(final YangInstanceIdentifier path, final CompositeNode node) {
        if(node == null) {
            return Optional.absent();
        }
        try {
            return Optional.<NormalizedNode<?, ?>>of(normalizer.toNormalized(path, node).getValue());
        } catch (final Exception e) {
            LOG.error("{}: Unable to normalize data for {}, data: {}", id, path, node, e);
            throw e;
        }
    }

    public ListenableFuture<Optional<NormalizedNode<?, ?>>> readOperationalData(final YangInstanceIdentifier path) {
        final ListenableFuture<RpcResult<CompositeNode>> future = rpc.invokeRpc(NETCONF_GET_QNAME, NetconfMessageTransformUtil.wrap(NETCONF_GET_QNAME, toFilterStructure(path)));

        return Futures.transform(future, new Function<RpcResult<CompositeNode>, Optional<NormalizedNode<?, ?>>>() {
            @Override
            public Optional<NormalizedNode<?, ?>> apply(final RpcResult<CompositeNode> result) {
                checkReadSuccess(result, path);

                final CompositeNode data = result.getResult().getFirstCompositeByName(NETCONF_DATA_QNAME);
                final CompositeNode node = (CompositeNode) findNode(data, path);

                return data == null ?
                        Optional.<NormalizedNode<?, ?>>absent() :
                        transform(path, node);
            }
        });
    }

    private static Node<?> findNode(final CompositeNode node, final YangInstanceIdentifier identifier) {

        Node<?> current = node;
        for (final YangInstanceIdentifier.PathArgument arg : identifier.getPathArguments()) {
            if (current instanceof SimpleNode<?>) {
                return null;
            } else if (current instanceof CompositeNode) {
                final CompositeNode currentComposite = (CompositeNode) current;

                current = currentComposite.getFirstCompositeByName(arg.getNodeType());
                if (current == null) {
                    current = currentComposite.getFirstCompositeByName(arg.getNodeType().withoutRevision());
                }
                if (current == null) {
                    current = currentComposite.getFirstSimpleByName(arg.getNodeType());
                }
                if (current == null) {
                    current = currentComposite.getFirstSimpleByName(arg.getNodeType().withoutRevision());
                }
                if (current == null) {
                    return null;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        // NOOP
    }

    @Override
    public ListenableFuture<Optional<NormalizedNode<?, ?>>> read(final LogicalDatastoreType store, final YangInstanceIdentifier path) {
        final YangInstanceIdentifier legacyPath = toLegacyPath(normalizer, path, id);

        switch (store) {
            case CONFIGURATION : {
                return readConfigurationData(legacyPath);
            }
            case OPERATIONAL : {
                return readOperationalData(legacyPath);
            }
        }

        throw new IllegalArgumentException(String.format("%s, Cannot read data %s for %s datastore, unknown datastore type", id, path, store));
    }

    static YangInstanceIdentifier toLegacyPath(final DataNormalizer normalizer, final YangInstanceIdentifier path, final RemoteDeviceId id) {
        try {
            return normalizer.toLegacy(path);
        } catch (final DataNormalizationException e) {
            throw new IllegalArgumentException(id + ": Cannot normalize path " + path, e);
        }
    }

    @Override
    public Object getIdentifier() {
        return this;
    }
}