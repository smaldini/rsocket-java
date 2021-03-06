/*
 * Copyright 2016 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.rsocket.fragmentation;

import io.rsocket.DuplexConnection;
import io.rsocket.Frame;
import io.rsocket.frame.FrameHeaderFlyweight;
import io.rsocket.util.NonBlockingHashMapLong;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Fragments and Re-assembles frames. MTU is number of bytes per fragment. The default is 1024 */
public class FragmentationDuplexConnection implements DuplexConnection {

  private final DuplexConnection source;
  private final NonBlockingHashMapLong<FrameReassembler> frameReassemblers =
      new NonBlockingHashMapLong<>();
  private final FrameFragmenter frameFragmenter;

  public FragmentationDuplexConnection(DuplexConnection source, int mtu) {
    this.source = source;
    this.frameFragmenter = new FrameFragmenter(mtu);
  }

  public static int getDefaultMTU() {
    if (Boolean.getBoolean("io.rsocket.fragmentation.enable")) {
      return Integer.getInteger("io.rsocket.fragmentation.mtu", 1024);
    }

    return 0;
  }

  @Override
  public double availability() {
    return source.availability();
  }

  @Override
  public Mono<Void> send(Publisher<Frame> frames) {
    return Flux.from(frames).concatMap(this::sendOne).then();
  }

  @Override
  public Mono<Void> sendOne(Frame frame) {
    if (frameFragmenter.shouldFragment(frame)) {
      return source.send(frameFragmenter.fragment(frame));
    } else {
      return source.sendOne(frame);
    }
  }

  @Override
  public Flux<Frame> receive() {
    return source
        .receive()
        .concatMap(
            frame -> {
              if (FrameHeaderFlyweight.FLAGS_F == (frame.flags() & FrameHeaderFlyweight.FLAGS_F)) {
                FrameReassembler frameReassembler = getFrameReassembler(frame);
                frameReassembler.append(frame);
                return Mono.empty();
              } else if (frameReassemblersContain(frame.getStreamId())) {
                FrameReassembler frameReassembler = removeFrameReassembler(frame.getStreamId());
                frameReassembler.append(frame);
                Frame reassembled = frameReassembler.reassemble();
                return Mono.just(reassembled);
              } else {
                return Mono.just(frame);
              }
            });
  }

  @Override
  public void dispose() {
    source.dispose();
  }

  @Override
  public boolean isDisposed() {
    return source.isDisposed();
  }

  @Override
  public Mono<Void> onClose() {
    return source
        .onClose()
        .doFinally(
            s -> {
              synchronized (FragmentationDuplexConnection.this) {
                frameReassemblers.values().forEach(FrameReassembler::dispose);

                frameReassemblers.clear();
              }
            });
  }

  private FrameReassembler getFrameReassembler(Frame frame) {
    FrameReassembler value, newValue;
    int streamId = frame.getStreamId();
    return ((value = frameReassemblers.get(streamId)) == null
            && (value =
                    frameReassemblers.putIfAbsent(streamId, newValue = new FrameReassembler(frame)))
                == null)
        ? newValue
        : value;
  }

  private FrameReassembler removeFrameReassembler(int streamId) {
    return frameReassemblers.remove(streamId);
  }

  private boolean frameReassemblersContain(int streamId) {
    return frameReassemblers.containsKey(streamId);
  }
}
