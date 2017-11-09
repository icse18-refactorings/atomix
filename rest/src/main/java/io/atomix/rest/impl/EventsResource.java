/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.rest.impl;

import com.google.common.util.concurrent.MoreExecutors;
import io.atomix.cluster.messaging.ClusterEventService;
import io.atomix.cluster.messaging.MessageSubject;
import io.atomix.serializer.Serializer;
import io.atomix.serializer.kryo.KryoNamespaces;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Events resource.
 */
@Path("/events")
public class EventsResource {
  private static final Serializer SERIALIZER = Serializer.using(KryoNamespaces.BASIC);

  /**
   * Returns an event log name.
   */
  private String getEventLogName(String subject, String id) {
    return String.format("%s-%s", subject, id);
  }

  @POST
  @Path("/{subject}")
  @Consumes(MediaType.TEXT_PLAIN)
  public Response publish(@PathParam("subject") String subject, @Context ClusterEventService eventService, String body) {
    eventService.broadcast(body, new MessageSubject(subject), SERIALIZER::encode);
    return Response.ok().build();
  }

  @GET
  @Path("/{subject}")
  @Produces(MediaType.TEXT_PLAIN)
  public void next(@PathParam("subject") String subject, @Context ClusterEventService eventService, @Context EventManager events, @Suspended AsyncResponse response) {
    EventLog<Consumer<String>, String> eventLog = events.getOrCreateEventLog(
        ClusterEventService.class, subject, l -> e -> l.addEvent(e));
    CompletableFuture<Void> openFuture;
    if (eventLog.open()) {
      openFuture = eventService.addSubscriber(new MessageSubject(subject), SERIALIZER::decode, eventLog.listener(), MoreExecutors.directExecutor());
    } else {
      openFuture = CompletableFuture.completedFuture(null);
    }

    openFuture.whenComplete((result, error) -> {
      if (error == null) {
        eventLog.nextEvent().whenComplete((event, eventError) -> {
          if (eventError == null) {
            response.resume(Response.ok(event).build());
          } else {
            response.resume(Response.noContent().build());
          }
        });
      } else {
        response.resume(Response.serverError().build());
      }
    });
  }

  @DELETE
  @Path("/{subject}")
  public Response delete(@PathParam("subject") String subject, @Context ClusterEventService eventService, @Context EventManager events) {
    EventLog<Consumer<String>, String> eventLog = events.removeEventLog(ClusterEventService.class, subject);
    if (eventLog != null && eventLog.close()) {
      eventService.removeSubscriber(new MessageSubject(subject));
    }
    return Response.ok().build();
  }

  @POST
  @Path("/{subject}/sub")
  public void subscribe(@PathParam("subject") String subject, @Context ClusterEventService eventService, @Context EventManager events, @Suspended AsyncResponse response) {
    String id = UUID.randomUUID().toString();
    EventLog<Consumer<String>, String> eventLog = events.getOrCreateEventLog(
        ClusterEventService.class, getEventLogName(subject, id), l -> e -> l.addEvent(e));
    eventService.addSubscriber(new MessageSubject(subject), SERIALIZER::decode, eventLog.listener(), MoreExecutors.directExecutor())
        .whenComplete((result, error) -> {
          if (error == null) {
            response.resume(Response.ok(id).build());
          } else {
            response.resume(Response.serverError().build());
          }
        });
  }

  @GET
  @Path("/{subject}/sub/{id}")
  @Produces(MediaType.TEXT_PLAIN)
  public void nextSession(@PathParam("subject") String subject, @QueryParam("id") String id, @Context EventManager events, @Suspended AsyncResponse response) {
    EventLog<Consumer<String>, String> eventLog = events.getEventLog(ClusterEventService.class, getEventLogName(subject, id));
    if (eventLog == null) {
      response.resume(Response.status(Status.NOT_FOUND).build());
      return;
    }

    eventLog.nextEvent().whenComplete((event, error) -> {
      if (error == null) {
        response.resume(Response.ok(event).build());
      } else {
        response.resume(Response.noContent().build());
      }
    });
  }

  @DELETE
  @Path("/{subject}/sub/{id}")
  public Response unsubscribe(@PathParam("subject") String subject, @QueryParam("id") String id, @Context ClusterEventService eventService, @Context EventManager events) {
    EventLog<Consumer<String>, String> eventLog = events.getEventLog(ClusterEventService.class, getEventLogName(subject, id));
    if (eventLog != null && eventLog.close()) {
      eventService.removeSubscriber(new MessageSubject(subject));
    }
    return Response.ok().build();
  }
}
