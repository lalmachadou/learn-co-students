/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.transport.bolt;

import com.alipay.remoting.Connection;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.InvokeContext;
import com.alipay.remoting.Url;
import com.alipay.remoting.exception.ConnectionClosedException;
import com.alipay.remoting.exception.DeserializationException;
import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.exception.SerializationException;
import com.alipay.remoting.rpc.RpcClient;
import com.alipay.remoting.rpc.exception.InvokeSendFailedException;
import com.alipay.remoting.rpc.exception.InvokeServerBusyException;
import com.alipay.remoting.rpc.exception.InvokeServerException;
import com.alipay.remoting.rpc.exception.InvokeTimeoutException;
import com.alipay.sofa.rpc.client.ProviderInfo;
import com.alipay.sofa.rpc.codec.bolt.SofaRpcSerializationRegister;
import com.alipay.sofa.rpc.common.RemotingConstants;
import com.alipay.sofa.rpc.common.RpcConfigs;
import com.alipay.sofa.rpc.common.RpcConstants;
import com.alipay.sofa.rpc.common.RpcOptions;
import com.alipay.sofa.rpc.common.utils.ClassLoaderUtils;
import com.alipay.sofa.rpc.context.RpcInternalContext;
import com.alipay.sofa.rpc.core.exception.RpcErrorType;
import com.alipay.sofa.rpc.core.exception.SofaRpcException;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.core.exception.SofaTimeOutException;
import com.alipay.sofa.rpc.core.invoke.SofaResponseCallback;
import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import com.alipay.sofa.rpc.event.ClientAfterSendEvent;
import com.alipay.sofa.rpc.event.ClientBeforeSendEvent;
import com.alipay.sofa.rpc.event.ClientSyncReceiveEvent;
import com.alipay.sofa.rpc.event.EventBus;
import com.alipay.sofa.rpc.ext.Extension;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.message.ResponseFuture;
import com.alipay.sofa.rpc.message.bolt.BoltFutureInvokeCallback;
import com.alipay.sofa.rpc.message.bolt.BoltInvokerCallback;
import com.alipay.sofa.rpc.message.bolt.BoltResponseFuture;
import com.alipay.sofa.rpc.transport.AbstractChannel;
import com.alipay.sofa.rpc.transport.ClientTransport;
import com.alipay.sofa.rpc.transport.ClientTransportConfig;

import java.net.InetSocketAddress;
import java.security.AlgorithmConstraints;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ?????????bolt??????????????????????????????????????????
 *
 * @author <a href="mailto:zhanggeng.zg@antfin.com">GengZhang</a>
 */
@Extension("bolt")
public class BoltClientTransport extends ClientTransport {

    /**
     * Logger for this class
     */
    private static final Logger                  LOGGER            = LoggerFactory.getLogger(BoltClientTransport.class);
    /**
     * Bolt rpc client
     */
    protected static final RpcClient             RPC_CLIENT        = new RpcClient();

    protected static final boolean               REUSE_CONNECTION  = RpcConfigs.getOrDefaultValue(
                                                                       RpcOptions.TRANSPORT_CONNECTION_REUSE, true);

    /**
     * Connection manager for reuse connection
     *
     * @since 5.4.0
     */
    protected static BoltClientConnectionManager connectionManager = REUSE_CONNECTION ? new ReuseBoltClientConnectionManager(
                                                                       true)
                                                                       : new AloneBoltClientConnectionManager(
                                                                           true);

    static {
        RPC_CLIENT.init();
        SofaRpcSerializationRegister.registerCustomSerializer();
    }

    /**
     * bolt?????????URL?????????
     */
    protected final Url                          url;

    /**
     * ???????????????????????????
     */
    protected volatile AtomicInteger             currentRequests   = new AtomicInteger(0);

    /**
     * Instant BoltClientTransport
     *
     * @param transportConfig ???????????????
     */
    protected BoltClientTransport(ClientTransportConfig transportConfig) {
        super(transportConfig);
        url = convertProviderToUrl(transportConfig, transportConfig.getProviderInfo());
    }

    /**
     * For convert provider to bolt url.
     *
     * @param transportConfig ClientTransportConfig
     * @param providerInfo    ProviderInfo
     * @return Bolt Url
     */
    protected Url convertProviderToUrl(ClientTransportConfig transportConfig, ProviderInfo providerInfo) {
        // Url???????????????????????????????????????????????????????????????
        Url boltUrl = new Url(providerInfo.toString(), providerInfo.getHost(), providerInfo.getPort());

        boltUrl.setConnectTimeout(transportConfig.getConnectTimeout());
        // ???????????????connNum????????????,??????slb???vip?????????
        final int connectionNum = transportConfig.getConnectionNum();
        if (connectionNum > 0) {
            boltUrl.setConnNum(connectionNum);
        } else {
            boltUrl.setConnNum(1);
        }
        boltUrl.setConnWarmup(false); // true??????
        if (RpcConstants.PROTOCOL_TYPE_BOLT.equals(providerInfo.getProtocolType())) {
            boltUrl.setProtocol(RemotingConstants.PROTOCOL_BOLT);
        } else {
            boltUrl.setProtocol(RemotingConstants.PROTOCOL_TR);
        }
        return boltUrl;
    }

    @Override
    public void connect() {
        fetchConnection();
    }

    @Override
    public void disconnect() {
        try {
            connectionManager.closeConnection(RPC_CLIENT, transportConfig, url);
        } catch (SofaRpcRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_CLOSE_CONNECTION), e);
        }
    }

    @Override
    public void destroy() {
        disconnect();
    }

    @Override
    public boolean isAvailable() {
        return connectionManager.isConnectionFine(RPC_CLIENT, transportConfig, url);
    }

    @Override
    public void setChannel(AbstractChannel channel) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public AbstractChannel getChannel() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public int currentRequests() {
        return currentRequests.get();
    }

    @Override
    public ResponseFuture asyncSend(SofaRequest request, int timeout) throws SofaRpcException {
        checkConnection();
        RpcInternalContext context = RpcInternalContext.getContext();
        InvokeContext boltInvokeContext = createInvokeContext(request);
        try {
            beforeSend(context, request);
            boltInvokeContext.put(RemotingConstants.INVOKE_CTX_RPC_CTX, context);
            return doInvokeAsync(request, context, boltInvokeContext, timeout);
        } catch (Exception e) {
            throw convertToRpcException(e);
        } finally {
            afterSend(context, boltInvokeContext, request);
        }
    }

    /**
     * ????????????
     *
     * @param request       ????????????
     * @param rpcContext    RPC???????????????
     * @param invokeContext ???????????????
     * @param timeoutMillis ????????????????????????
     * @throws RemotingException    ??????????????????
     * @throws InterruptedException ????????????
     * @since 5.2.0
     */
    protected ResponseFuture doInvokeAsync(SofaRequest request, RpcInternalContext rpcContext,
                                           InvokeContext invokeContext, int timeoutMillis)
        throws RemotingException, InterruptedException {
        SofaResponseCallback listener = request.getSofaResponseCallback();
        if (listener != null) {
            // callback??????
            InvokeCallback callback = new BoltInvokerCallback(transportConfig.getConsumerConfig(),
                transportConfig.getProviderInfo(), listener, request, rpcContext,
                ClassLoaderUtils.getCurrentClassLoader());
            // ????????????
            RPC_CLIENT.invokeWithCallback(url, request, invokeContext, callback, timeoutMillis);
            return null;
        } else {
            // future ?????? callback
            BoltResponseFuture future = new BoltResponseFuture(request, timeoutMillis);
            InvokeCallback callback = new BoltFutureInvokeCallback(transportConfig.getConsumerConfig(),
                transportConfig.getProviderInfo(), future, request, rpcContext,
                ClassLoaderUtils.getCurrentClassLoader());
            // ????????????
            RPC_CLIENT.invokeWithCallback(url, request, invokeContext, callback, timeoutMillis);
            future.setSentTime();
            return future;
        }
    }

    @Override
    public SofaResponse syncSend(SofaRequest request, int timeout) throws SofaRpcException {
        checkConnection();
        RpcInternalContext context = RpcInternalContext.getContext();
        InvokeContext boltInvokeContext = createInvokeContext(request);
        SofaResponse response = null;
        SofaRpcException throwable = null;
        try {
            beforeSend(context, request);
            response = doInvokeSync(request, boltInvokeContext, timeout);
            return response;
        } catch (Exception e) { // ????????????
            throwable = convertToRpcException(e);
            throw throwable;
        } finally {
            afterSend(context, boltInvokeContext, request);
            if (EventBus.isEnable(ClientSyncReceiveEvent.class)) {
                EventBus.post(new ClientSyncReceiveEvent(transportConfig.getConsumerConfig(),
                    transportConfig.getProviderInfo(), request, response, throwable));
            }
        }
    }

    /**
     * ????????????
     *
     * @param request       ????????????
     * @param invokeContext ???????????????
     * @param timeoutMillis ????????????????????????
     * @return ????????????
     * @throws RemotingException    ??????????????????
     * @throws InterruptedException ????????????
     * @since 5.2.0
     */
    protected SofaResponse doInvokeSync(SofaRequest request, InvokeContext invokeContext, int timeoutMillis)
        throws RemotingException, InterruptedException {
        return (SofaResponse) RPC_CLIENT.invokeSync(url, request, invokeContext, timeoutMillis);
    }

    @Override
    public void oneWaySend(SofaRequest request, int timeout) throws SofaRpcException {
        checkConnection();
        RpcInternalContext context = RpcInternalContext.getContext();
        InvokeContext invokeContext = createInvokeContext(request);
        SofaRpcException throwable = null;
        try {
            beforeSend(context, request);
            doOneWay(request, invokeContext, timeout);
        } catch (Exception e) { // ????????????
            throwable = convertToRpcException(e);
            throw throwable;
        } finally {
            afterSend(context, invokeContext, request);
            if (EventBus.isEnable(ClientSyncReceiveEvent.class)) {
                EventBus.post(new ClientSyncReceiveEvent(transportConfig.getConsumerConfig(),
                    transportConfig.getProviderInfo(), request, null, throwable));
            }
        }
    }

    /**
     * ????????????
     *
     * @param request       ????????????
     * @param invokeContext ???????????????
     * @param timeoutMillis ????????????????????????
     * @throws RemotingException    ??????????????????
     * @throws InterruptedException ????????????
     * @since 5.2.0
     */
    protected void doOneWay(SofaRequest request, InvokeContext invokeContext, int timeoutMillis)
        throws RemotingException, InterruptedException {
        RPC_CLIENT.oneway(url, request, invokeContext);
    }

    /**
     * ??????????????????????????????RPC??????
     *
     * @param e ??????
     * @return RPC??????
     */
    protected SofaRpcException convertToRpcException(Exception e) {
        SofaRpcException exception;
        if (e instanceof SofaRpcException) {
            exception = (SofaRpcException) e;
        }
        // ??????
        else if (e instanceof InvokeTimeoutException) {
            exception = new SofaTimeOutException(e);
        }
        // ????????????
        else if (e instanceof InvokeServerBusyException) {
            exception = new SofaRpcException(RpcErrorType.SERVER_BUSY, e);
        }
        // ?????????
        else if (e instanceof SerializationException) {
            boolean isServer = ((SerializationException) e).isServerSide();
            exception = isServer ? new SofaRpcException(RpcErrorType.SERVER_SERIALIZE, e)
                : new SofaRpcException(RpcErrorType.CLIENT_SERIALIZE, e);
        }
        // ????????????
        else if (e instanceof DeserializationException) {
            boolean isServer = ((DeserializationException) e).isServerSide();
            exception = isServer ? new SofaRpcException(RpcErrorType.SERVER_DESERIALIZE, e)
                : new SofaRpcException(RpcErrorType.CLIENT_DESERIALIZE, e);
        }
        // ???????????????
        else if (e instanceof ConnectionClosedException) {
            exception = new SofaRpcException(RpcErrorType.CLIENT_NETWORK, e);
        }
        // ?????????????????????
        else if (e instanceof InvokeSendFailedException) {
            exception = new SofaRpcException(RpcErrorType.CLIENT_NETWORK, e);
        }
        // ?????????????????????
        else if (e instanceof InvokeServerException) {
            exception = new SofaRpcException(RpcErrorType.SERVER_UNDECLARED_ERROR, e.getCause());
        }
        // ???????????????
        else {
            exception = new SofaRpcException(RpcErrorType.CLIENT_UNDECLARED_ERROR, e);
        }
        return exception;
    }

    protected InvokeContext createInvokeContext(SofaRequest request) {
        InvokeContext invokeContext = new InvokeContext();
        invokeContext.put(InvokeContext.BOLT_CUSTOM_SERIALIZER, request.getSerializeType());
        invokeContext.put(RemotingConstants.HEAD_TARGET_SERVICE, request.getTargetServiceUniqueName());
        invokeContext.put(RemotingConstants.HEAD_METHOD_NAME, request.getMethodName());
        String genericType = (String) request.getRequestProp(RemotingConstants.HEAD_GENERIC_TYPE);
        if (genericType != null) {
            invokeContext.put(RemotingConstants.HEAD_GENERIC_TYPE, genericType);
        }
        return invokeContext;
    }

    /**
     * ???????????????????????????
     *
     * @param context RPC?????????
     * @param request ????????????
     */
    protected void beforeSend(RpcInternalContext context, SofaRequest request) {
        currentRequests.incrementAndGet();
        context.setLocalAddress(localAddress());
        if (EventBus.isEnable(ClientBeforeSendEvent.class)) {
            EventBus.post(new ClientBeforeSendEvent(request));
        }
    }

    /**
     * ?????????????????????????????????????????????????????????????????????
     *
     * @param context       RPC?????????
     * @param invokeContext bolt???????????????
     * @param request       ????????????
     */
    protected void afterSend(RpcInternalContext context, InvokeContext invokeContext, SofaRequest request) {
        currentRequests.decrementAndGet();
        if (RpcInternalContext.isAttachmentEnable()) {
            putToContextIfNotNull(invokeContext, InvokeContext.CLIENT_CONN_CREATETIME, context,
                RpcConstants.INTERNAL_KEY_CONN_CREATE_TIME);
        }
        if (EventBus.isEnable(ClientAfterSendEvent.class)) {
            EventBus.post(new ClientAfterSendEvent(request));
        }
    }

    @Override
    public void receiveRpcResponse(SofaResponse response) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void handleRpcRequest(SofaRequest request) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public InetSocketAddress remoteAddress() {
        Connection connection = fetchConnection();
        return connection == null ? null : connection.getRemoteAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        Connection connection = fetchConnection();
        return connection == null ? null : connection.getLocalAddress();
    }

    protected void checkConnection() throws SofaRpcException {

        Connection connection = fetchConnection();
        if (connection == null) {
            throw new SofaRpcException(RpcErrorType.CLIENT_NETWORK, "connection is null");
        }
        if (!connection.isFine()) {
            throw new SofaRpcException(RpcErrorType.CLIENT_NETWORK, "connection is not fine");
        }
    }

    protected void putToContextIfNotNull(InvokeContext invokeContext, String oldKey,
                                         RpcInternalContext context, String key) {
        Object value = invokeContext.get(oldKey);
        if (value != null) {
            context.setAttachment(key, value);
        }
    }

    public Connection fetchConnection() {
        return connectionManager.getConnection(RPC_CLIENT, transportConfig, url);
    }
}
