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
package org.apache.dubbo.admin.controller;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.admin.governance.service.ProviderService;
import org.apache.dubbo.admin.governance.service.RouteService;
import org.apache.dubbo.admin.registry.common.domain.Access;
import org.apache.dubbo.admin.registry.common.domain.Route;
import org.apache.dubbo.admin.registry.common.dto.CreateAccessControl;
import org.apache.dubbo.admin.registry.common.route.RouteRule;
import org.apache.dubbo.admin.web.pulltool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/accesses")
public class AccessesController {
    private static final Logger logger = LoggerFactory.getLogger(AccessesController.class);

    @Resource
    private RouteService routeService;
    @Resource
    private ProviderService providerService;

    @RequestMapping("/search")
    public List<Access> searchAccesses(@RequestParam(required = false) String address,
                                       @RequestParam(required = false) String service) {
        address = Tool.getIP(address);
        List<Route> routes;
        if (StringUtils.isNotBlank(service)) {
            routes = routeService.findForceRouteByService(service.trim());
        } else if (StringUtils.isNotBlank(address)) {
            routes = routeService.findForceRouteByAddress(address.trim());
        } else {
            routes = routeService.findAllForceRoute();
        }
        List<Access> accesses = new ArrayList<Access>();
        AtomicLong idGenerator = new AtomicLong();
        for (Route route : routes) {
            this.initMatchAndFilterRule(route);
            Map<String, RouteRule.MatchPair> rule = new HashMap<String, RouteRule.MatchPair>();
            try {
                rule = RouteRule.parseRule(route.getMatchRule());
            } catch (ParseException e) {
                logger.error("parse rule error", e);
            }
            RouteRule.MatchPair pair = rule.get("consumer.host");

            if (pair != null) {
                for (String host : pair.getMatches()) {
                    Access access = new Access();
                    access.setId(idGenerator.incrementAndGet());
                    access.setAddress(host);
                    access.setService(route.getService());
                    access.setAllow(false);
                    accesses.add(access);
                }
                for (String host : pair.getUnmatches()) {
                    Access access = new Access();
                    access.setId(idGenerator.incrementAndGet());
                    access.setAddress(host);
                    access.setService(route.getService());
                    access.setAllow(true);
                    accesses.add(access);
                }
            }
        }
        return accesses;
    }

    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public void deleteAccesses(@RequestBody List<Access> accesses) throws Exception {
        Map<String, Set<String>> prepareToDeleate = new HashMap<String, Set<String>>();
        for (Access access : accesses) {
            Set<String> addresses = prepareToDeleate.get(access.getService());
            if (addresses == null) {
                prepareToDeleate.put(access.getService(), new HashSet<String>());
                addresses = prepareToDeleate.get(access.getService());
            }
            addresses.add(access.getAddress());
        }

        for (String service : prepareToDeleate.keySet()) {
            List<Route> routes = routeService.findForceRouteByService(service);
            if (routes == null || routes.size() == 0) {
                continue;
            }
            for (Route blackwhitelist : routes) {
                this.initMatchAndFilterRule(blackwhitelist);
                RouteRule.MatchPair pairs = RouteRule.parseRule(blackwhitelist.getMatchRule()).get("consumer.host");
                Set<String> matches = new HashSet<String>(pairs.getMatches());
                Set<String> unmatches = new HashSet<String>(pairs.getUnmatches());
                for (String pair : pairs.getMatches()) {
                    for (String address : prepareToDeleate.get(service)) {
                        if (pair.equals(address)) {
                            matches.remove(pair);
                            break;
                        }
                    }
                }
                for (String pair : pairs.getUnmatches()) {
                    for (String address : prepareToDeleate.get(service)) {
                        if (pair.equals(address)) {
                            unmatches.remove(pair);
                            break;
                        }
                    }
                }
                if (matches.size() == 0 && unmatches.size() == 0) {
                    routeService.deleteRoute(blackwhitelist.getId());
                } else {
                    Map<String, RouteRule.MatchPair> condition = new HashMap<String, RouteRule.MatchPair>();
                    condition.put("consumer.host", new RouteRule.MatchPair(matches, unmatches));
                    StringBuilder sb = new StringBuilder();
                    RouteRule.contidionToString(sb, condition);
                    blackwhitelist.setMatchRule(sb.toString());
                    // TODO use new rule
                    blackwhitelist.setRule(blackwhitelist.getMatchRule() + " => " + blackwhitelist.getFilterRule());
                    routeService.updateRoute(blackwhitelist);
                }
            }
        }
    }

    @RequestMapping("/services")
    public List<String> findServices() {
        return Tool.sortSimpleName(providerService.findServices());
    }

    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public void createAccesses(@RequestBody CreateAccessControl createAccessControl) throws Exception {
        List<String> services = createAccessControl.getServices();
        List<String> addresses = createAccessControl.getAddressList();
        boolean allowed = createAccessControl.isAllowed();

        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("Services is required.");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("Addresses is required.");
        }

        for (String aimService : services) {
            boolean isFirst = false;
            List<Route> routes = routeService.findForceRouteByService(aimService);
            Route route = null;
            if (routes == null || routes.size() == 0) {
                isFirst = true;
                route = new Route();
                route.setService(aimService);
                route.setForce(true);
                route.setName(aimService + " blackwhitelist");
                route.setFilterRule("false");
                route.setEnabled(true);
            } else {
                route = routes.get(0);
                this.initMatchAndFilterRule(route);
            }
            Map<String, RouteRule.MatchPair> when = null;
            RouteRule.MatchPair matchPair = null;
            if (isFirst) {
                when = new HashMap<String, RouteRule.MatchPair>();
                matchPair = new RouteRule.MatchPair(new HashSet<String>(), new HashSet<String>());
                when.put("consumer.host", matchPair);
            } else {
                when = RouteRule.parseRule(route.getMatchRule());
                matchPair = when.get("consumer.host");
            }
            for (String consumerAddress : addresses) {
                if (allowed) {
                    matchPair.getUnmatches().add(Tool.getIP(consumerAddress));
                } else {
                    matchPair.getMatches().add(Tool.getIP(consumerAddress));
                }
            }
            StringBuilder sb = new StringBuilder();
            RouteRule.contidionToString(sb, when);
            route.setMatchRule(sb.toString());
            // TODO use new rule
            route.setRule(route.getMatchRule() + " => " + route.getFilterRule());
            if (isFirst) {
                routeService.createRoute(route);
            } else {
                routeService.updateRoute(route);
            }

        }
    }

    // for old rule
    // TODO org.apache.dubbo.admin.registry.common.domain.Route#toUrl() URL.encode(getRule()) -> URL.encode(getMatchRule() + " => " + getFilterRule())
    private void initMatchAndFilterRule(Route route) {
        String[] rules = route.getRule().split(" => ");
        if (rules.length != 2) {
            throw new IllegalArgumentException("Illegal Route Condition Rule");
        }
        route.setMatchRule(rules[0]);
        route.setFilterRule(rules[1]);
    }
}
