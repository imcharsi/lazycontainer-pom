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

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface RetrieveDelegate<IDTYPE,BEANTYPE> {
    int size(Map<Object,Object> condition);

    Collection<IDTYPE> getIds(Object[] columns, boolean[] ascending, Map<Object,Object> condition);

    Object getIdByIndex(int index, Object[] columns, boolean[] ascending, Map<Object,Object> condition);

    List<BEANTYPE> getList(int startIndex, int numberOfItems, Object[] columns, boolean[] ascending, Map<Object,Object> condition);

    int indexOfId(IDTYPE itemId, Object[] columns, boolean[] ascending, Map<Object,Object> condition);

    /**
     * for example,
     *
     * public interface SomethingDao extends RetrieveDelegate<...> {
     *     ...
     * }
     *
     * @Stateless
     * ...
     * public class SomethingDaoEjb implmenets SomethingDao {
     *     ...
     * }
     *
     * ...
     *
     * SomehingDao dao = ...;
     * LazyContainer<...> container = new LazyContainer<>(...);
     * container.setRetrieveDelegate(dao);
     */
}
