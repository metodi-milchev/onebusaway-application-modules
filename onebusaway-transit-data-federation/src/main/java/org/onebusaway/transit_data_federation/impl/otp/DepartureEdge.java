package org.onebusaway.transit_data_federation.impl.otp;

import java.util.Date;
import java.util.List;

import org.onebusaway.transit_data_federation.services.StopTimeService;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.onebusaway.transit_data_federation.services.tripplanner.StopTimeInstance;
import org.opentripplanner.routing.algorithm.NegativeWeightException;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TraverseOptions;
import org.opentripplanner.routing.core.TraverseResult;
import org.opentripplanner.routing.core.Vertex;

public class DepartureEdge extends AbstractEdge {

  private final StopEntry _stop;

  public DepartureEdge(GraphContext context, StopEntry stop) {
    super(context);
    _stop = stop;
  }

  @Override
  public Vertex getFromVertex() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Vertex getToVertex() {
    throw new UnsupportedOperationException();
  }

  @Override
  public TraverseResult traverse(State s0, TraverseOptions options)
      throws NegativeWeightException {

    TraverseResult result = null;

    StopTimeService stopTimeService = _context.getStopTimeService();
    long time = s0.getTime();

    /**
     * Look for departures in the next X minutes
     */
    Date from = new Date(time);
    Date to = new Date(SupportLibrary.getNextTimeWindow(_context, time));

    List<StopTimeInstance> instances = stopTimeService.getStopTimeInstancesInRange(
        from, to, _stop);

    for (StopTimeInstance instance : instances) {

      long departureTime = instance.getDepartureTime();

      // Prune anything that doesn't have a departure in the proper range, since
      // the stopTimeService method will also return instances that arrive in
      // the target interval as well
      if (departureTime < from.getTime() || to.getTime() <= departureTime)
        continue;

      // If this is the last stop time in the block, don't continue
      if (!SupportLibrary.hasNextStopTime(instance))
        continue;

      int dwellTime = (int) ((departureTime - time) / 1000);
      State s1 = new State(departureTime);
      TraverseResult r = new TraverseResult(dwellTime, s1, this);
      //r.setVertex(new BlockDepartureVertex(_context, instance));

      result = r.addToExistingResultChain(result);
    }

    // In addition to all the departures, we can just remain waiting at the stop
    int dwellTime = (int) ((to.getTime() - from.getTime()) / 1000);
    State s1 = new State(to.getTime());
    TraverseResult r = new TraverseResult(dwellTime, s1, this);
    //r.setVertex(new WaitingAtStopVertex(_context, _stop, to.getTime()));

    result = r.addToExistingResultChain(result);

    return result;
  }

  @Override
  public TraverseResult traverseBack(State s0, TraverseOptions options)
      throws NegativeWeightException {
    State s1 = s0.clone();
    return new TraverseResult(0, s1, this);
  }

  @Override
  public double getDistance() {
    return 0;
  }
}