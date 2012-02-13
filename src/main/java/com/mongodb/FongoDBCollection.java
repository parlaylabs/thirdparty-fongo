package com.mongodb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.bson.types.ObjectId;

import com.foursquare.fongo.ExpressionParser;
import com.foursquare.fongo.Filter;
import com.foursquare.fongo.Option;
import com.foursquare.fongo.UpdateEngine;

public class FongoDBCollection extends DBCollection {
  
  private final class IdComparator implements Comparator<Object> {
    @Override
    public int compare(Object o1, Object o2) {
      return expressionParser.compareObjects(o1, o2);
    }
  }

  final static String ID_KEY = "_id";
  private final FongoDB fongoDb;
  private final Map<Object, DBObject> objects = new HashMap<Object, DBObject>();
  private final ExpressionParser expressionParser = new ExpressionParser();
  
  public FongoDBCollection(FongoDB db, String name) {
    super(db, name);
    this.fongoDb = db;
  }
  
  @Override
  public synchronized WriteResult insert(DBObject[] arr, WriteConcern concern, DBEncoder encoder) throws MongoException {
    for (DBObject obj : arr) {
      fInsert(obj);
    }
    return new WriteResult(fongoDb.okResult(), concern);
  }


  protected void fInsert(DBObject obj) {
    if (!obj.containsField(ID_KEY)) {
      obj.put(ID_KEY, new ObjectId());
    }
    Object id = obj.get(ID_KEY);
    objects.put(id, obj);
  }

  @Override
  public synchronized WriteResult update(DBObject q, DBObject o, boolean upsert, boolean multi, WriteConcern concern,
      DBEncoder encoder) throws MongoException {
    boolean idOnlyUpdate = q.containsField(ID_KEY) && q.keySet().size() == 1;
    if (o.containsField(ID_KEY) && !idOnlyUpdate){
      throw new MongoException.DuplicateKey(0, "can't update " + ID_KEY);
    }
    if (idOnlyUpdate){
      if (!o.containsField(ID_KEY)) {
        o.put(ID_KEY, q.get(ID_KEY));
      }
      fInsert(o);
    } else {
      Filter filter = expressionParser .buildFilter(q);
      boolean wasFound = false;
      UpdateEngine updateEngine = new UpdateEngine(q, false);
      for (DBObject obj : objects.values()) {
        if (filter.apply(obj)){
          wasFound = true;
          updateEngine.doUpdate(obj, o);
          if (!multi){
            break;
          }
        }
      }
      if (!wasFound && upsert){
        BasicDBObject newObject = createUpsertObject(q);
        fInsert(updateEngine.doUpdate(newObject, o));
      }
    }
    return new WriteResult(fongoDb.okResult(), concern);
  }
  
  public List<Object> idsIn(DBObject query) {
    Object idValue = query.get(ID_KEY);
    if (idValue == null || query.keySet().size() > 0) {
      return Collections.emptyList();
    } else if (idValue instanceof DBObject ){
      DBObject idDbObject = (DBObject)idValue;
      List inList = (List)idDbObject.get(ExpressionParser.IN);
      if (inList != null){
        return inList;
      } else {
        return Collections.emptyList();
      }
    } else {
      return Collections.singletonList(idValue);
    }
  }

  protected  BasicDBObject createUpsertObject(DBObject q) {
    BasicDBObject newObject = new BasicDBObject();
    for (String key : q.keySet()){
      Object value = q.get(key);
      boolean okValue = true;
      if (value instanceof DBObject){
        for (String innerKey : ((DBObject) value).keySet()){
          if (innerKey.startsWith("$")){
            okValue = false;
          }
        }
      }
      if (okValue){
        newObject.put(key, value);
      }
    }
    return newObject;
  }

  @Override
  protected void doapply(DBObject o) {
  }

  @Override
  public synchronized WriteResult remove(DBObject o, WriteConcern concern, DBEncoder encoder) throws MongoException {
    List<Object> idList = idsIn(o);
    if (!idList.isEmpty()) {
      for (Object id : idList){
        objects.remove(id);        
      }
    } else {
      Filter filter = expressionParser.buildFilter(o);
      for (Iterator<DBObject> iter = objects.values().iterator(); iter.hasNext(); ) {
        DBObject dbo = iter.next();
        if (filter.apply(dbo)){
          iter.remove();
        }
      }
    }
    return new WriteResult(fongoDb.okResult(), concern);
  }



  @Override
  public void createIndex(DBObject keys, DBObject options, DBEncoder encoder) throws MongoException {
    // TODO Auto-generated method stub
    
  }

  
  @Override
  synchronized Iterator<DBObject> __find(DBObject ref, DBObject fields, int numToSkip, int batchSize, int limit, int options,
      ReadPreference readPref, DBDecoder decoder) throws MongoException {
    List<Object> idList = idsIn(ref);
    ArrayList<DBObject> results = new ArrayList<DBObject>();
    if (!idList.isEmpty()) {
      for (Object id : idList){
        DBObject result = objects.get(id);
        if (result != null){
          results.add(result);          
        }
      }
    } else {
      DBObject orderby = null;
      if (ref.containsField("query") && ref.containsField("orderby")) {
        orderby = (DBObject)ref.get("orderby");
        ref = (DBObject)ref.get("query");
      }
      
      Filter filter = expressionParser.buildFilter(ref);
      int foundCount = 0;
      int upperLimit = Integer.MAX_VALUE;
      if (limit > 0) {
        upperLimit = limit;
      }
      Collection<DBObject> objectsToSearch = sortObjects(orderby, expressionParser);
      int seen = 0;
      for (Iterator<DBObject> iter = objectsToSearch.iterator(); iter.hasNext() && foundCount <= upperLimit; seen++) {
        DBObject dbo = iter.next();
        if (seen >= numToSkip){
          if (filter.apply(dbo)) {
            foundCount++;
            results.add(dbo);
          }
        }
      }
    }
    if (results.size() == 0){
      return null;
    } else {
      return results.iterator();      
    }
  }

  protected Collection<DBObject> sortObjects(DBObject orderby, final ExpressionParser expressionParser) {
    Collection<DBObject> objectsToSearch = objects.values();
    if (orderby != null) {
      Set<String> orderbyKeys = orderby.keySet();
      if (!orderbyKeys.isEmpty()){
        final String sortKey = orderbyKeys.iterator().next();
        final int sortDirection = (Integer)orderby.get(sortKey);
        ArrayList<DBObject> objectList = new ArrayList<DBObject>(objects.values());
        Collections.sort(objectList, new Comparator<DBObject>(){
          @Override
          public int compare(DBObject o1, DBObject o2) {
            Option<Object> o1option = expressionParser.getEmbeddedValue(sortKey, o1);
            Option<Object> o2option = expressionParser.getEmbeddedValue(sortKey, o2);
            if (o1option.isEmpty()) {
              return -1 * sortDirection;
            } else if (o2option.isEmpty()) {
              return sortDirection;
            } else {
              Comparable o1Value = expressionParser.typecast(sortKey, o1option.get(), Comparable.class);
              Comparable o2Value = expressionParser.typecast(sortKey, o2option.get(), Comparable.class);
              
              return o1Value.compareTo(o2Value) * sortDirection;
            }
          }});
        return objectList;
      }
    }
    return objectsToSearch;
  }

  public synchronized int fCount(DBObject object) {
    return objects.size();
  }

  public synchronized DBObject fFindAndModify(DBObject query, DBObject update, DBObject sort, boolean remove,
      boolean returnNew, boolean upsert) {
    
    Filter filter = expressionParser.buildFilter(query);
 
    Collection<DBObject> objectsToSearch = sortObjects(sort, expressionParser);
    DBObject beforeObject = null;
    DBObject afterObject = null;
    UpdateEngine updateEngine = new UpdateEngine(query, false);
    for (Iterator<DBObject> iter = objectsToSearch.iterator(); iter.hasNext();) {
      DBObject dbo = iter.next();
      if (filter.apply(dbo)) {
        beforeObject = dbo;
        if (!remove) {
          afterObject = new BasicDBObject();
          afterObject.putAll(beforeObject);
          fInsert(updateEngine.doUpdate(afterObject, update));
        } else {
          remove(dbo);
          return dbo;
        }
      }
    }
    if (beforeObject != null && !returnNew){
      return beforeObject;
    }
    if (beforeObject == null && upsert && !remove){
      afterObject = createUpsertObject(query);
      fInsert(updateEngine.doUpdate(afterObject, update));
    }
    return afterObject;
  }
  

}
