/*
 * Copyright (c) 2013 Kang Woo, Lee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vaadin.addons.lazycontainer;

import com.vaadin.data.Container;
import com.vaadin.data.Item;
import com.vaadin.data.Property;
import com.vaadin.data.util.AbstractBeanContainer;
import com.vaadin.data.util.AbstractContainer;
import com.vaadin.data.util.BeanItem;
import com.vaadin.data.util.VaadinPropertyDescriptor;
import com.vaadin.data.util.filter.UnsupportedFilterException;
import com.vaadin.server.VaadinServletService;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import javax.servlet.http.HttpServletRequest;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

public class LazyContainer<IDTYPE, BEANTYPE> extends AbstractContainer implements Container.Indexed, Container.Filterable, Container.Sortable {
    @Getter
    private Map<String, VaadinPropertyDescriptor<BEANTYPE>> descriptorMap;
    @Getter(AccessLevel.PROTECTED)
    private Class<BEANTYPE> type;
    private Constructor<BeanItem> beanItemConstructor;
    @Getter
    @Setter
    private RetrieveDelegate<IDTYPE, BEANTYPE> retrieveDelegate;
    private Integer cachedSize;
    private Map<Integer, IDTYPE> index2Id;
    private Map<IDTYPE, Integer> id2Index;
    private Map<IDTYPE, BeanItem<BEANTYPE>> byId;
    private Long threadId;
    @Getter
    private Map<Object, Object> condition;
    private List<Container.Filter> filterList;
    private boolean sortAscending[];
    private Object sortPropertyId[];
    @Getter
    @Setter
    private AbstractBeanContainer.BeanIdResolver<IDTYPE, BEANTYPE> beanIdResolver;

    public LazyContainer() {
    }

    public LazyContainer(Class<BEANTYPE> type) {
        init(type);
    }

    protected void init(Class<BEANTYPE> type) {
        try {
            // in this implementation, this uses something java reflect api to use vaadin internal functions.
            Method declaredMethod = BeanItem.class.getDeclaredMethod("getPropertyDescriptors", Class.class);
            declaredMethod.setAccessible(true);
            this.descriptorMap = (Map<String, VaadinPropertyDescriptor<BEANTYPE>>) declaredMethod.invoke(null, type);
            this.type = type;
            this.beanItemConstructor = BeanItem.class.getDeclaredConstructor(Object.class, Map.class);
            this.beanItemConstructor.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.index2Id = new HashMap<>();
        this.id2Index = new HashMap<>();
        this.byId = new HashMap<>();
        this.cachedSize = null;
        this.threadId = null;
        this.condition = new HashMap<>();
    }

    @Override
    public Item getItem(Object itemId) {
        return byId.get(itemId);
    }

    @Override
    public Collection<?> getContainerPropertyIds() {
        return Collections.unmodifiableCollection(descriptorMap.keySet());
    }

    @Override
    public Collection<IDTYPE> getItemIds() {
        return retrieveDelegate.getIds(sortPropertyId, sortAscending, condition);
    }

    @Override
    public Property getContainerProperty(Object itemId, Object propertyId) {
        return getItem(itemId).getItemProperty(propertyId);
    }

    @Override
    public Class<?> getType(Object propertyId) {
        return descriptorMap.get(propertyId).getPropertyType();
    }

    @Override
    public int size() {
        // in vaadin 7.1, there are many size() calling. it is expensive.
        // so we need something workaround.
        if (needRefreshCachedSize()) {
            refreshCachedSize();
            cachedSize = retrieveDelegate.size(condition);
        }

        return cachedSize;
    }

    @Override
    public boolean containsId(Object itemId) {
        return byId.containsKey(itemId);
    }

    @Override
    public int indexOfId(Object itemId) {
        // this method is used by ComboBox with ItemCaptionMode.PROPERTY
        // in this situation, we should retrieve item's index directly.
        return retrieveDelegate.indexOfId((IDTYPE) itemId, sortPropertyId, sortAscending, condition);
    }

    @Override
    public Object getIdByIndex(int index) {
        return retrieveDelegate.getIdByIndex(index, sortPropertyId, sortAscending, condition);
    }

    @Override
    public List<IDTYPE> getItemIds(int startIndex, int numberOfItems) {
        id2Index.clear();
        index2Id.clear();
        byId.clear();
        ArrayList<IDTYPE> arrayList = new ArrayList<>();
        int cnt = startIndex;
        List<BEANTYPE> list = retrieveDelegate.getList(startIndex, numberOfItems, sortPropertyId, sortAscending, condition);
        if (list.size() != numberOfItems) {
            // after calling size() and before following getItemIds(),
            // if table rows are changed ?
            clearCachedSize();
        }
        AbstractBeanContainer.BeanIdResolver<IDTYPE, BEANTYPE> beanIdResolver = getBeanIdResolver();
        for (BEANTYPE bean : list) {
            IDTYPE id = beanIdResolver.getIdForBean(bean);
            id2Index.put(id, cnt);
            index2Id.put(cnt, id);
            try {
                byId.put(id, beanItemConstructor.newInstance(bean, descriptorMap));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            arrayList.add(id);
            cnt += 1;
        }

        return arrayList;
    }

    @Override
    public Object nextItemId(Object itemId) {
        int index = indexOfId(itemId);
        if (index >= 0 && index < size() - 1) {
            return getIdByIndex(index + 1);
        } else {
            return null;
        }
    }

    @Override
    public Object prevItemId(Object itemId) {
        int index = indexOfId(itemId);
        if (index > 0) {
            return getIdByIndex(index - 1);
        } else {
            return null;
        }
    }

    @Override
    public Object firstItemId() {
        if (size() > 0) {
            return getIdByIndex(0);
        } else {
            return null;
        }
    }

    @Override
    public Object lastItemId() {
        if (size() > 0) {
            return getIdByIndex(size() - 1);
        } else {
            return null;
        }
    }

    @Override
    public boolean isFirstId(Object itemId) {
        if (itemId == null) {
            return false;
        }
        return itemId.equals(firstItemId());
    }

    @Override
    public boolean isLastId(Object itemId) {
        if (itemId == null) {
            return false;
        }
        return itemId.equals(lastItemId());
    }

    // this implementation is read-only.

    @Override
    public Item addItem(Object itemId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object addItem() throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeItem(Object itemId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addContainerProperty(Object propertyId, Class<?> type, Object defaultValue) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeContainerProperty(Object propertyId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAllItems() throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object addItemAfter(Object previousItemId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Item addItemAfter(Object previousItemId, Object newItemId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Item addItemAt(int index, Object newItemId) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object addItemAt(int index) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addContainerFilter(Filter filter) throws UnsupportedFilterException {
        if (filterList == null) {
            filterList = new ArrayList<>();
        }
        filterList.add(filter);
        // you need something process about Filter.
        // for example, in ComboBox's SimpleStringFilter
        //
        // public class SomeExtendedContainer extended LazyContainer ... {
        // ...
        // public void addContainerFilter(Filter filter) {
        //     super.addContainerFilter(filter);
        //     condition.put(SomeKey, filterString);
        // }
    }

    @Override
    public void removeContainerFilter(Filter filter) {
        if (filterList != null) {
            filterList.remove(filter);
        }
        // public class SomeExtendedContainer extended LazyContainer ... {
        // ...
        // void SomeExtendedContainer.removeContainerFilter(Filter filter) {
        //     super.removeContainerFilter(filter);
        //     condition.remove(SomeKey);
        // }
    }

    @Override
    public void removeAllContainerFilters() {
        if (filterList != null) {
            for (Container.Filter filter : filterList) {
                removeContainerFilter(filter);
            }
        }
    }

    @Override
    public Collection<Filter> getContainerFilters() {
        return Collections.unmodifiableCollection(filterList);
    }

    @Override
    public void sort(Object[] propertyId, boolean[] ascending) {
        this.sortAscending = ascending;
        this.sortPropertyId = propertyId;
    }

    @Override
    public Collection<?> getSortableContainerPropertyIds() {
        return Collections.emptyList();
    }

    private WeakReference<HttpServletRequest> weakRef = null;

    protected boolean needRefreshCachedSize() {
        // in older version, thread's id was used.
        // but in some environment, thread's id was not unique each request.
        // so use servletrequest's object identity insted.
        final HttpServletRequest currentServletRequest = VaadinServletService.getCurrentServletRequest();
        if (weakRef == null || weakRef.get() == null)
            return true;
        if (weakRef.get() == currentServletRequest)
            return false;
        return true;
    }

    protected void refreshCachedSize() {
        final HttpServletRequest currentServletRequest = VaadinServletService.getCurrentServletRequest();
        weakRef = new WeakReference<>(currentServletRequest);
    }

    protected void clearCachedSize() {
        weakRef = null;
    }
}
