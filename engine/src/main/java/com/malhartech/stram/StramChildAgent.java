/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.stram;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.malhartech.api.DAG.OperatorWrapper;
import com.malhartech.api.DAG.StreamDecl;
import com.malhartech.api.OperatorCodec;
import com.malhartech.bufferserver.util.Codec;
import com.malhartech.stram.OperatorDeployInfo.InputDeployInfo;
import com.malhartech.stram.OperatorDeployInfo.OutputDeployInfo;
import com.malhartech.stram.PhysicalPlan.PTComponent;
import com.malhartech.stram.PhysicalPlan.PTContainer;
import com.malhartech.stram.PhysicalPlan.PTInput;
import com.malhartech.stram.PhysicalPlan.PTOperator;
import com.malhartech.stram.PhysicalPlan.PTOutput;
import com.malhartech.stram.StreamingContainerUmbilicalProtocol.ContainerHeartbeatResponse;
import com.malhartech.stram.StreamingContainerUmbilicalProtocol.StreamingContainerContext;
import com.malhartech.stram.StreamingContainerUmbilicalProtocol.StreamingNodeHeartbeat;
import com.malhartech.stram.StreamingContainerUmbilicalProtocol.StreamingNodeHeartbeat.DNodeState;

/**
 *
 * Representation of a child container in the master<p>
 * <br>
 */
public class StramChildAgent {
  private static final Logger LOG = LoggerFactory.getLogger(StramChildAgent.class);

  public static class ContainerStartRequest extends DeployRequest {
    final PTContainer container;

    ContainerStartRequest(PTContainer container, AtomicInteger ackCountdown, AtomicInteger executeWhenZero) {
      super(ackCountdown, executeWhenZero);
      this.container = container;
    }
  }

  public static class DeployRequest {
    final AtomicInteger ackCountdown;
    final AtomicInteger executeWhenZero;
    private List<PTOperator> nodes;

    public DeployRequest(AtomicInteger ackCountdown, AtomicInteger executeWhenZero) {
      this.ackCountdown = ackCountdown;
      this.executeWhenZero = executeWhenZero;
    }

    void cancel() {
      if (ackCountdown != null) {
        ackCountdown.set(-1);
      }
    }

    void ack() {
      ackCountdown.decrementAndGet();
    }

    void setNodes(List<PTOperator> nodes) {
      this.nodes = nodes;
    }

    @Override
    public String toString()
    {
      return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
        .append("operators", this.nodes)
        //.append("streams", this.streams)
        .append("executeWhenZero", this.executeWhenZero)
        .toString();
    }
  }

  public static class UndeployRequest extends DeployRequest {
    public UndeployRequest(PTContainer container,
        AtomicInteger ackCountdown, AtomicInteger executeWhenZero) {
      super(ackCountdown, executeWhenZero);
    }
  }

  class MovingAverage {
    private final int periods;
    private final long[] values;
    private int index = 0;
    private boolean filled = false;

    MovingAverage(int periods) {
      this.periods = periods;
      this.values = new long[periods];
    }

    void add(long val) {
      values[index++] = val;
      if (index == periods) {
        filled = true;
      }
      index = index % periods;
    }

    long getAvg() {
      long sum = 0;
      for (int i=0; i<periods; i++) {
        sum += values[i];
      }

      if (!filled) {
        return index == 0 ? 0 : sum/index;
      } else {
        return sum/periods;
      }
    }
  }

  class OperatorStatus
  {
    StreamingNodeHeartbeat lastHeartbeat;
    final PTOperator operator;
    final PTContainer container;
    long totalTuplesProcessed;
    long totalTuplesEmitted;
    MovingAverage tuplesProcessedPSMA10 = new MovingAverage(10);
    MovingAverage tuplesEmittedPSMA10 = new MovingAverage(10);

    private OperatorStatus(PTContainer container, PTOperator operator) {
      this.operator = operator;
      this.container = container;
    }

    public boolean isIdle()
    {
      if ((lastHeartbeat != null && DNodeState.IDLE.name().equals(lastHeartbeat.getState()))) {
        return true;
      }
      return false;
    }
  }

  public StramChildAgent(PTContainer container, StreamingContainerContext initCtx) {
    this.container = container;
    this.initCtx = initCtx;
    this.operators = new HashMap<String, OperatorStatus>(container.operators.size());
    for (PTOperator operator : container.operators) {
      this.operators.put(operator.id, new OperatorStatus(container, operator));
    }
  }

  boolean shutdownRequested = false;
  boolean isComplete = false;
  long lastHeartbeatMillis = 0;
  long lastCheckpointRequestMillis = 0;
  long createdMillis = System.currentTimeMillis();
  final PTContainer container;
  final Map<String, OperatorStatus> operators;
  final StreamingContainerContext initCtx;
  private final OperatorCodec nodeSerDe = StramUtils.getNodeSerDe(null);
  DeployRequest pendingRequest = null;

  private final ConcurrentLinkedQueue<DeployRequest> requests = new ConcurrentLinkedQueue<DeployRequest>();

  public StreamingContainerContext getInitContext() {
    //ContainerHeartbeatResponse rsp = pollRequest();
    //if (rsp != null && rsp.deployRequest != null) {
    //  initCtx.nodeList = rsp.deployRequest;
    //}
    return initCtx;
  }

  public boolean hasPendingWork() {
    return !this.requests.isEmpty() || this.pendingRequest != null;
  }

  private void ackPendingRequest() {
    if (pendingRequest != null) {
      if (pendingRequest.ackCountdown != null) {
        pendingRequest.ackCountdown.decrementAndGet();
        pendingRequest = null;
      }
    }
  }

  public void addRequest(DeployRequest r) {
    this.requests.add(r);
    LOG.info("Adding request {} {}", container.containerId, r);
  }

  protected ConcurrentLinkedQueue<DeployRequest> getRequests() {
    return this.requests;
  }

  public ContainerHeartbeatResponse pollRequest() {
    ackPendingRequest();
    //LOG.debug("Number of pending requests for container {}: {}", this.container.containerId, requests.size());
    DeployRequest r = requests.peek();
    if (r == null) {
      return null;
    }

    if (r.executeWhenZero != null) {
      if (r.executeWhenZero.get() < 0) {
        // cancelled
        return null;
      } else if (r.executeWhenZero.get() > 0) {
        ContainerHeartbeatResponse rsp = new ContainerHeartbeatResponse();
        LOG.debug("Request for {} blocked: {}", this.container.containerId, r);
        rsp.hasPendingRequests = true;
        // keep polling
        return rsp;
      }
    }

    // process
    if (!requests.remove(r)) {
        return null;
    }

    this.pendingRequest = r;
    ContainerHeartbeatResponse rsp = new ContainerHeartbeatResponse();
    if (r.nodes != null) {
      List<OperatorDeployInfo> nodeList = getDeployInfoList(r.nodes);
      if (r instanceof UndeployRequest) {
        rsp.undeployRequest = nodeList;
      } else {
        rsp.deployRequest = nodeList;
      }
    }

    rsp.hasPendingRequests = (!this.requests.isEmpty());
    return rsp;
  }

  boolean isIdle() {
    for (OperatorStatus operatorStatus : this.operators.values()) {
      if (!operatorStatus.isIdle()) {
        return false;
      }
    }
    return true;
  }

  public List<OperatorDeployInfo> getDeployInfo() {
    return getDeployInfoList(container.operators);
  }

  /**
   * Create deploy info for StramChild.
   * @param container
   * @param deployNodes
   * @param checkpoints
   * @return StreamingContainerContext
   */
  private List<OperatorDeployInfo> getDeployInfoList(List<PTOperator> deployNodes) {

    if (container.bufferServerAddress == null) {
      throw new IllegalStateException("No buffer server address assigned");
    }

    Map<OperatorDeployInfo, PTOperator> nodes = new LinkedHashMap<OperatorDeployInfo, PTOperator>();
    Map<String, OutputDeployInfo> publishers = new LinkedHashMap<String, OutputDeployInfo>();

    for (PTOperator node : deployNodes) {
      OperatorDeployInfo ndi = createOperatorDeployInfo(node.id, node.getLogicalNode());
      long checkpointWindowId = node.getRecoveryCheckpoint();
      if (checkpointWindowId > 0) {
        LOG.debug("Operator {} recovery checkpoint {}", node.id, Codec.getStringWindowId(checkpointWindowId));
        ndi.checkpointWindowId = checkpointWindowId;
      }
      nodes.put(ndi, node);
      ndi.inputs = new ArrayList<InputDeployInfo>(node.inputs.size());
      ndi.outputs = new ArrayList<OutputDeployInfo>(node.outputs.size());

      for (PTOutput out : node.outputs) {
        final StreamDecl streamDecl = out.logicalStream;
        // buffer server or inline publisher
        OutputDeployInfo portInfo = new OutputDeployInfo();
        portInfo.declaredStreamId = streamDecl.getId();
        portInfo.portName = out.portName;

        if (!(streamDecl.isInline() && out.isDownStreamInline())) {
          portInfo.bufferServerHost = node.container.bufferServerAddress.getHostName();
          portInfo.bufferServerPort = node.container.bufferServerAddress.getPort();
          if (streamDecl.getSerDeClass() != null) {
            portInfo.serDeClassName = streamDecl.getSerDeClass().getName();
          }
        } else {
          // target set below
          //portInfo.inlineTargetNodeId = "-1subscriberInOtherContainer";
        }

        ndi.outputs.add(portInfo);
        publishers.put(node.id + "/" + streamDecl.getId(), portInfo);
      }
    }

    // after we know all publishers within container, determine subscribers

    for (Map.Entry<OperatorDeployInfo, PTOperator> nodeEntry : nodes.entrySet()) {
      OperatorDeployInfo ndi = nodeEntry.getKey();
      PTOperator node = nodeEntry.getValue();
      for (PTInput in : node.inputs) {
        final StreamDecl streamDecl = in.logicalStream;
        // input from other node(s) OR input adapter
        if (streamDecl.getSource() == null) {
          throw new IllegalStateException("source is null: " + in);
        }
        PTComponent sourceNode = in.source;

        InputDeployInfo inputInfo = new InputDeployInfo();
        inputInfo.declaredStreamId = streamDecl.getId();
        inputInfo.portName = in.portName;
        inputInfo.sourceNodeId = sourceNode.id;
        inputInfo.sourcePortName = in.logicalStream.getSource().getPortName();
        if (in.partition != null) {
          inputInfo.partitionKeys = Arrays.asList(in.partition);
        }

        if (streamDecl.isInline() && sourceNode.container == node.container) {
          // inline input (both operators in same container and inline hint set)
          OutputDeployInfo outputInfo = publishers.get(sourceNode.id + "/" + streamDecl.getId());
          if (outputInfo == null) {
            throw new IllegalStateException("Missing publisher for inline stream " + streamDecl);
          }
        } else {
          // buffer server input
          // FIXME: address to come from upstream output port, should be assigned first
          InetSocketAddress addr = in.source.container.bufferServerAddress;
          if (addr == null) {
            LOG.warn("upstream address not assigned: " + in.source);
            addr = container.bufferServerAddress;
          }
          inputInfo.bufferServerHost = addr.getHostName();
          inputInfo.bufferServerPort = addr.getPort();
          if (streamDecl.getSerDeClass() != null) {
            inputInfo.serDeClassName = streamDecl.getSerDeClass().getName();
          }
        }
        ndi.inputs.add(inputInfo);
      }
    }

    return new ArrayList<OperatorDeployInfo>(nodes.keySet());
  }

  /**
   * Create deploy info for operator.<p>
   * <br>
   * @param dnodeId
   * @param nodeDecl
   * @return {@link com.malhartech.stram.OperatorDeployInfo}
   *
   */
  private OperatorDeployInfo createOperatorDeployInfo(String dnodeId, OperatorWrapper operator)
  {
    OperatorDeployInfo ndi = new OperatorDeployInfo();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try {
      this.nodeSerDe.write(operator.getOperator(), os);
      ndi.serializedNode = os.toByteArray();
      os.close();
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize " + operator + "(" + operator.getOperator().getClass() + ")", e);
    }
    ndi.declaredId = operator.getId();
    ndi.id = dnodeId;
    return ndi;
  }

}
