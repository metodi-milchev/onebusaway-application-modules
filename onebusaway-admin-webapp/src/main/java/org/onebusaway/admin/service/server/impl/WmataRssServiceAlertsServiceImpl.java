package org.onebusaway.admin.service.server.impl;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TimeRange;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TranslatedString.Translation;
import com.google.transit.realtime.GtfsRealtimeConstants;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.HttpStatus;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.onebusaway.admin.service.server.WmataRssServiceAlertsService;
import org.onebusaway.transit_data.model.ListBean;
import org.onebusaway.transit_data.model.RouteBean;
import org.onebusaway.transit_data.model.service_alerts.*;
import org.onebusaway.transit_data.services.TransitDataService;
import org.onebusaway.transit_data_federation.services.service_alerts.ServiceAlerts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
class WmataRssServiceAlertsSerivceImpl implements WmataRssServiceAlertsService {

    private static Logger _log = LoggerFactory.getLogger(WmataRssServiceAlertsSerivceImpl.class);

    private String wmataAgencyId = "1"; //todo Externalize agency ID and urls
    private String serviceStatusUrlString = "http://www.metroalerts.info/rss.aspx?bus";
    private String serviceAdvisoryUrlString = "http://www.wmata.com/rider_tools/metro_service_status/feeds/bus_Advisories.xml";
    private HttpClient httpClient = new HttpClient();
    private SAXBuilder builder = new SAXBuilder();
    private SimpleDateFormat sdf = new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss zzz");
    private ScheduledExecutorService _executor;
    @Autowired
    private TransitDataService _transitDataService;
    private Map<String, String> routeShortNameToRouteIdMap;
    private Map<String, ServiceAlertBean> wmataAlertCache;
    private ObjectMapper mapper = new ObjectMapper();
    private boolean _removeAgencyIds = true;
    private FeedMessage _feed = null;


    @PostConstruct
    public void start() throws Exception {
        _executor = Executors.newSingleThreadScheduledExecutor();
        _executor.scheduleAtFixedRate(new RefreshDataTask(), 0, 1, TimeUnit.HOURS);
        _executor.scheduleAtFixedRate(new PollWmataRssTask(), 0, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop() throws IOException {
        if (_executor != null)
            _executor.shutdownNow();
    }

    @Override
    public FeedMessage getServlceAlertFeed() {
      return _feed;
    }
    
    protected List<ServiceAlertBean> pollServiceAdvisoryRssFeed() throws Exception {
        List<ServiceAlertBean> alerts = new ArrayList<ServiceAlertBean>();
        HttpMethod httpget = new GetMethod(serviceAdvisoryUrlString);
        int response = httpClient.executeMethod(httpget);
        if (response != HttpStatus.SC_OK) {
            throw new Exception("WMATA service status poll failed, returned status code: " + response);
        }

        Document doc = builder.build(httpget.getResponseBodyAsStream());

        List<Element> elements = doc.getRootElement().getChild("channel").getChildren("item");
        String language = doc.getRootElement().getChild("channel").getChildText("language");
        if(language == null)
            language = "en-us";  //they don't send language for this feed currently, perhaps they'll start?
        for(Element itemElement : elements){
            String title = itemElement.getChild("title").getValue();
            String link = itemElement.getChild("link").getValue();
            String description = itemElement.getChild("description").getValue();
            String pubDateString = itemElement.getChild("pubDate").getValue();
            String guid = itemElement.getChild("guid").getValue();
            Date pubDate = sdf.parse(pubDateString);
            List<SituationAffectsBean> affectedRouteIds = getRouteIds(title);
            ServiceAlertBean serviceAlertBean = new ServiceAlertBean();
            serviceAlertBean.setSource("WMATA");
            serviceAlertBean.setAllAffects(affectedRouteIds);
            serviceAlertBean.setSeverity(ESeverity.UNKNOWN);
            serviceAlertBean.setSummaries(Arrays.asList(new NaturalLanguageStringBean[]{new NaturalLanguageStringBean(description, language)}));
            serviceAlertBean.setReason(ServiceAlerts.ServiceAlert.Cause.UNKNOWN_CAUSE.name());
            SituationConsequenceBean situationConsequenceBean = new SituationConsequenceBean();
            situationConsequenceBean.setEffect(EEffect.SIGNIFICANT_DELAYS);
            serviceAlertBean.setConsequences(Arrays.asList(new SituationConsequenceBean[]{situationConsequenceBean}));
            serviceAlertBean.setCreationTime(pubDate.getTime());
            serviceAlertBean.setDescriptions(Arrays.asList(new NaturalLanguageStringBean[]{new NaturalLanguageStringBean(description, language)}));
            serviceAlertBean.setId(wmataAgencyId + "_" + guid);
            serviceAlertBean.setUrls(Arrays.asList(new NaturalLanguageStringBean[]{new NaturalLanguageStringBean(link, language)}));
            alerts.add(serviceAlertBean);
        }
        return alerts;
    }

    protected List<ServiceAlertBean>  pollServiceStatusRssFeed() throws Exception {
        List<ServiceAlertBean> alerts = new ArrayList<ServiceAlertBean>();
        HttpMethod httpget = new GetMethod(serviceStatusUrlString);
        int response = httpClient.executeMethod(httpget);
        if (response != HttpStatus.SC_OK) {
            throw new Exception("WMATA service status poll failed, returned status code: " + response);
        }

        Document doc = builder.build(httpget.getResponseBodyAsStream());


        List<Element> elements = doc.getRootElement().getChild("channel").getChildren("item");
        String language = doc.getRootElement().getChild("channel").getChildText("language");
        for(Element itemElement : elements){
            String title = itemElement.getChild("title").getValue();
            String link = itemElement.getChild("link").getValue();
            String description = itemElement.getChild("description").getValue();
            String pubDateString = itemElement.getChild("pubDate").getValue();
            String guid = itemElement.getChild("guid").getValue();
            Date pubDate = sdf.parse(pubDateString);
            List<SituationAffectsBean> affectedRouteIds = getRouteIds(title);
            ServiceAlertBean serviceAlertBean = new ServiceAlertBean();
            serviceAlertBean.setSource("WMATA");
            serviceAlertBean.setAllAffects(affectedRouteIds);
            serviceAlertBean.setSeverity(ESeverity.UNKNOWN);
            serviceAlertBean.setSummaries(Arrays.asList(new NaturalLanguageStringBean[]{new NaturalLanguageStringBean(description, language)}));
            serviceAlertBean.setReason(ServiceAlerts.ServiceAlert.Cause.UNKNOWN_CAUSE.name());
            SituationConsequenceBean situationConsequenceBean = new SituationConsequenceBean();
            situationConsequenceBean.setEffect(EEffect.SIGNIFICANT_DELAYS);
            serviceAlertBean.setConsequences(Arrays.asList(new SituationConsequenceBean[]{situationConsequenceBean}));
            serviceAlertBean.setCreationTime(pubDate.getTime());
            serviceAlertBean.setDescriptions(Arrays.asList(new NaturalLanguageStringBean[]{new NaturalLanguageStringBean(description, language)}));
            serviceAlertBean.setId(wmataAgencyId + "_" + guid);
            serviceAlertBean.setUrls(Arrays.asList(new NaturalLanguageStringBean[]{new NaturalLanguageStringBean(link, language)}));
            alerts.add(serviceAlertBean);
        }
        return alerts;
    }

    private List<SituationAffectsBean> getRouteIds(String description){
        String[] routeShortNames = description.split("\\:")[0].split("\\,");
        List<SituationAffectsBean> affectedRoutes = new ArrayList<SituationAffectsBean>();
        for(int i = 0; i < routeShortNames.length; i++) {
            String routeShortName = routeShortNames[i];
            routeShortName = routeShortName.toUpperCase().trim();
            String routeId = routeShortNameToRouteIdMap.get(routeShortName);
            if(routeId != null){
                SituationAffectsBean situationAffectsBean = new SituationAffectsBean();
                situationAffectsBean.setAgencyId(wmataAgencyId);
                situationAffectsBean.setRouteId(routeId);
                affectedRoutes.add(situationAffectsBean);
            }else{
                _log.warn("No route found for route short name " + routeShortName);
            }
        }
        return affectedRoutes;
    }

    private class PollWmataRssTask implements Runnable {


        @Override
        public void run() {
            try {
                if(routeShortNameToRouteIdMap == null)
                    return;

                ListBean<ServiceAlertBean> currentObaAlerts = _transitDataService.getAllServiceAlertsForAgencyId(wmataAgencyId);
                for(ServiceAlertBean serviceAlertBean : currentObaAlerts.getList()){
                    if(!wmataAlertCache.keySet().contains(serviceAlertBean.getId())
                            && serviceAlertBean.getSource() != null
                            && serviceAlertBean.getSource().equals("WMATA")){
                        wmataAlertCache.put(serviceAlertBean.getId(), serviceAlertBean);
                    }
                }

                List<ServiceAlertBean> rssAlerts = new ArrayList<ServiceAlertBean>();
                try{
                    rssAlerts.addAll(pollServiceAdvisoryRssFeed());
                }catch (Exception e){
                    _log.warn(e.getMessage());
                    e.printStackTrace();
                }

                try{
                    rssAlerts.addAll(pollServiceStatusRssFeed());
                }catch (Exception e){
                    _log.warn(e.getMessage());
                    e.printStackTrace();
                }

                Map<String, ServiceAlertBean> currentRssAlertMap = new HashMap<String, ServiceAlertBean>();
                for(ServiceAlertBean alert : rssAlerts){
                    currentRssAlertMap.put(alert.getId(), alert);
                }

                Iterator<String> cachedAlertsGuidIter = wmataAlertCache.keySet().iterator();
                //first, check for expired alerts and existing alerts that have been updated
                while(cachedAlertsGuidIter.hasNext()){
                    String guid = cachedAlertsGuidIter.next();
                    if(!currentRssAlertMap.keySet().contains(guid)){
                        _log.info("Removing expired WMATA alert with guid " + guid);
                        // TODO
//                        _transitDataService.removeServiceAlert(guid);
                        cachedAlertsGuidIter.remove();
                    }else{
                        ServiceAlertBean currentAlert = wmataAlertCache.get(guid);
                        ServiceAlertBean rssAlert = currentRssAlertMap.get(guid);
                        if(rssAlert.getCreationTime() > currentAlert.getCreationTime()) {
                            _log.info("Updating WMATA alert with guid " + guid);
                            wmataAlertCache.put(guid, rssAlert);
                            // TODO
//                            _transitDataService.updateServiceAlert(rssAlert);
                        }
                    }
                }

                //now create alerts for any new guids on the RSS feed
                for(String currentRssGuid : currentRssAlertMap.keySet()){
                    if(!wmataAlertCache.keySet().contains(currentRssGuid)){
                        _log.info("Creating WMATA alert with guid " + currentRssGuid);
                        // TODO
//                        _transitDataService.createServiceAlert(wmataAgencyId, currentRssAlertMap.get(currentRssGuid));
                        wmataAlertCache.put(currentRssGuid, currentRssAlertMap.get(currentRssGuid));
                    }
                }
                
                updateAlerts(wmataAlertCache);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        
        
        private void updateAlerts(Map<String, ServiceAlertBean> wmataAlertCache) {
          FeedMessage.Builder feed = FeedMessage.newBuilder();
          FeedHeader.Builder header = feed.getHeaderBuilder();
          header.setGtfsRealtimeVersion(GtfsRealtimeConstants.VERSION);
          fillFeedMessage(feed, wmataAlertCache);
          _feed = feed.build();
        }

        // this code borrowed from AlertsForAgencyAction
        private void fillFeedMessage(FeedMessage.Builder feedEntity,
            Map<String, ServiceAlertBean> wmataAlertCache) {
          
          List<ServiceAlertBean> alerts = new ArrayList<ServiceAlertBean>();
          for (Entry<String, ServiceAlertBean> beanEntry: wmataAlertCache.entrySet()) {
            alerts.add(beanEntry.getValue());
          }
          ListBean<ServiceAlertBean> alertsBean = new ListBean<ServiceAlertBean>();
          toAlert(feedEntity, alertsBean);
        }

        private Alert toAlert(FeedMessage.Builder feed, ListBean<ServiceAlertBean> alerts) {
          for (ServiceAlertBean serviceAlert : alerts.getList()) {
            FeedEntity.Builder entity = feed.addEntityBuilder();
            entity.setId(Integer.toString(feed.getEntityCount()));
            Alert.Builder alert = entity.getAlertBuilder();

            fillTranslations(serviceAlert.getSummaries(),
                alert.getHeaderTextBuilder());
            fillTranslations(serviceAlert.getDescriptions(),
                alert.getDescriptionTextBuilder());

            if (serviceAlert.getActiveWindows() != null) {
              for (TimeRangeBean range : serviceAlert.getActiveWindows()) {
                TimeRange.Builder timeRange = alert.addActivePeriodBuilder();
                if (range.getFrom() != 0) {
                  timeRange.setStart(range.getFrom() / 1000);
                }
                if (range.getTo() != 0) {
                  timeRange.setEnd(range.getTo() / 1000);
                }
              }
            }

            if (serviceAlert.getAllAffects() != null) {
              for (SituationAffectsBean affects : serviceAlert.getAllAffects()) {
                EntitySelector.Builder entitySelector = alert.addInformedEntityBuilder();
                if (affects.getAgencyId() != null) {
                  entitySelector.setAgencyId(affects.getAgencyId());
                }
                if (affects.getRouteId() != null) {
                  entitySelector.setRouteId(normalizeId(affects.getRouteId()));
                }
                if (affects.getTripId() != null) {
                  TripDescriptor.Builder trip = entitySelector.getTripBuilder();
                  trip.setTripId(normalizeId(affects.getTripId()));
                  entitySelector.setTrip(trip);
                }
                if (affects.getStopId() != null) {
                  entitySelector.setStopId(normalizeId(affects.getStopId()));
                }
              }
            }
          }
          return null;
        }
    }

    private void fillTranslations(List<NaturalLanguageStringBean> input,
        TranslatedString.Builder output) {
      for (NaturalLanguageStringBean nls : input) {
        Translation.Builder translation = output.addTranslationBuilder();
        translation.setText(nls.getValue());
        if (nls.getLang() != null) {
          translation.setLanguage(nls.getLang());
        }
      }
    }
    
    protected String normalizeId(String id) {
      if (_removeAgencyIds) {
        int index = id.indexOf('_');
        if (index != -1) {
          id = id.substring(index + 1);
        }
      }
      return id;
    }
    
    private class RefreshDataTask implements Runnable {

        @Override
        public void run() {
            try {
                ListBean<RouteBean> routes =  _transitDataService.getRoutesForAgencyId(wmataAgencyId);
                Map<String, String> mutableRouteMap = new HashMap<String, String>();
                for(RouteBean route : routes.getList()){
                    mutableRouteMap.put(route.getShortName().toUpperCase(), route.getId());
                }
                routeShortNameToRouteIdMap = Collections.unmodifiableMap(mutableRouteMap);
                wmataAlertCache = new HashMap<String, ServiceAlertBean>();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}