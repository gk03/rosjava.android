/*
 * Copyright (C) 2011 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.ros.android.hokuyo;

import com.google.common.base.Preconditions;

import org.ros.message.Duration;
import org.ros.message.MessageListener;
import org.ros.message.std_msgs.Time;
import org.ros.node.Node;
import org.ros.node.NodeMain;
import org.ros.node.parameter.ParameterTree;
import org.ros.node.topic.Publisher;

/**
 * @author damonkohler@google.com (Damon Kohler)
 */
public class LaserScanPublisher implements NodeMain {

  private final LaserScannerDevice scipDevice;

  /**
   * The offset from our local clock to the actual wall clock time. This is
   * necessary because we cannot set time on android devices accurately enough
   * at the moment.
   */
  private Duration wallClockOffset;

  private Node node;
  private Publisher<org.ros.message.sensor_msgs.LaserScan> publisher;

  /**
   * We need a way to adjust time stamps because it is not (easily) possible to
   * change a tablet's clock.
   */
  public LaserScanPublisher(LaserScannerDevice scipDevice) {
    this.scipDevice = scipDevice;
  }

  @Override
  public void main(final Node node) throws Exception {
    Preconditions.checkState(this.node == null);
    this.node = node;
    ParameterTree params = node.newParameterTree();
    final String laserTopic = params.getString("~laser_topic", "laser");
    final String laserFrame = params.getString("~laser_frame", "laser");
    wallClockOffset = new Duration(0);
    publisher = node.newPublisher(node.resolveName(laserTopic),
        "sensor_msgs/LaserScan");
    node.newSubscriber("/wall_clock", "std_msgs/Time",
        new MessageListener<org.ros.message.std_msgs.Time>() {
          @Override
          public void onNewMessage(Time message) {
            wallClockOffset = message.data.subtract(node.getCurrentTime());
          }
        });
    scipDevice.startScanning(new LaserScanListener() {
      @Override
      public void onNewLaserScan(LaserScan scan) {
        org.ros.message.sensor_msgs.LaserScan message = toLaserScanMessage(
            laserFrame, scan);
        publisher.publish(message);
      }
    });
  }

  @Override
  public void shutdown() {
    if (node != null) {
      node.shutdown();
      node = null;
    }
    scipDevice.shutdown();
  }

  /**
   * Construct a LaserScan message from sensor readings and the laser
   * configuration.
   * 
   * Also gets rid of readings that don't contain any information.
   * 
   * Some laser scanners have blind areas before and after the actual detection
   * range. These are indicated by the frontStep and the lastStep properties of
   * the laser's configuration. Since the blind values never change, we can just
   * ignore them when copying the range readings.
   * 
   * @param laserFrame
   *          The laser's sensor frame.
   * @param scan
   *          The actual range readings.
   * @return A new LaserScan message
   */
  private org.ros.message.sensor_msgs.LaserScan toLaserScanMessage(
      String laserFrame, LaserScan scan) {
    org.ros.message.sensor_msgs.LaserScan message = node.getMessageFactory()
        .newMessage("sensor_msgs/LaserScan");
    LaserScannerConfiguration configuration = scipDevice.getConfiguration();
    
    message.angle_increment = configuration.getAngleIncrement();
    message.angle_min = configuration.getMinimumAngle();
    message.angle_max = configuration.getMaximumAngle();
    message.ranges = new float[configuration.getLastStep()
        - configuration.getFirstStep()];
    for (int i = 0; i < message.ranges.length; i++) {
      message.ranges[i] = (float) (scan.getRanges().get(
          i + configuration.getFirstStep()) / 1000.0);
    }
    message.time_increment = configuration.getTimeIncrement();
    message.scan_time = configuration.getScanTime();
    message.range_min = (float) (configuration.getMinimumMeasurment() / 1000.0);
    message.range_max = (float) (configuration.getMaximumMeasurement() / 1000.0);
    message.header.frame_id = laserFrame;
    message.header.stamp = new org.ros.message.Time(scan.getTimeStamp())
        .add(wallClockOffset);
    return message;
  }

}
