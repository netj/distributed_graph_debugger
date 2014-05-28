
package org.apache.giraph.debugger.examples.simpledebug;

import java.io.IOException;

import org.apache.giraph.Algorithm;
import org.apache.giraph.conf.LongConfOption;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.BasicComputation;
import org.apache.giraph.graph.Vertex;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.log4j.Logger;

/**
 * Debug version of SimpleShortestPathsComputation.
 */
@Algorithm(
    name = "Shortest paths",
    description = "Finds all shortest paths from a selected vertex"
)
public class BuggySimpleShortestPathsComputation extends BasicComputation<
    LongWritable, DoubleWritable, FloatWritable, DoubleWritable> {

  /** The shortest paths id */
  public static final LongConfOption SOURCE_ID =
      new LongConfOption("SimpleShortestPathsVertex.sourceId", 1,
          "The shortest paths id");
  /** Class logger */
  private static final Logger LOG =
      Logger.getLogger(BuggySimpleShortestPathsComputation.class);

  /**
   * Is this vertex the source id?
   *
   * @param vertex Vertex
   * @return True if the source id
   */
  private boolean isSource(Vertex<LongWritable, ?, ?> vertex) {
    return vertex.getId().get() == SOURCE_ID.get(getConf());
  }

  @Override
  public void compute(
      Vertex<LongWritable, DoubleWritable, FloatWritable> vertex,
      Iterable<DoubleWritable> messages) throws IOException {
    // We do a dummy read of the aggregator below because for now we only intercept an aggregator
    // if at least one vertex reads it.
    LongWritable aggregatedValue  = getAggregatedValue(
      SimpleShortestPathsMaster.NV_DISTANCE_LESS_THAN_THREE_AGGREGATOR);
    if (aggregatedValue != null) {
      System.out.println("NV_DISTANCE_LESS_THAN_THREE_AGGREGATOR: "
        + aggregatedValue.get());
    }
    if (getSuperstep() == 0) {
      vertex.setValue(new DoubleWritable(isSource(vertex) ? 0d : Double.MAX_VALUE));
    }
    double previousValue = vertex.getValue().get();
    double minDist = previousValue;
    for (DoubleWritable message : messages) {
      minDist = Math.min(minDist, message.get());
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Vertex " + vertex.getId() + " got minDist = " + minDist +
          " vertex value = " + vertex.getValue());
    }
    if (minDist < vertex.getValue().get() || (getSuperstep() == 0 && minDist == 0)) {
      vertex.setValue(new DoubleWritable(minDist));
      for (Edge<LongWritable, FloatWritable> edge : vertex.getEdges()) {
        double distance = minDist + edge.getValue().get();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Vertex " + vertex.getId() + " sent to " +
              edge.getTargetVertexId() + " = " + distance);
        }
        // INTENTIONAL BUG:Instead of sending the distance (i.e. by adding edge values),
        // we send minDist, which is the vertex value.
        sendMessage(edge.getTargetVertexId(), new DoubleWritable(minDist));
      }
    }
    if (previousValue > 3 && minDist <= 3) {
      aggregate(SimpleShortestPathsMaster.NV_DISTANCE_LESS_THAN_THREE_AGGREGATOR, 
        new LongWritable(1));
    }
    vertex.voteToHalt();
  }
}
