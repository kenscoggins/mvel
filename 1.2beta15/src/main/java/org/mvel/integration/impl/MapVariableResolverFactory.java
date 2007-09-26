/**
 * MVEL (The MVFLEX Expression Language)
 *
 * Copyright (C) 2007 Christopher Brock, MVFLEX/Valhalla Project and the Codehaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.mvel.integration.impl;

import org.mvel.integration.VariableResolver;
import org.mvel.integration.VariableResolverFactory;

import java.util.HashMap;
import java.util.Map;

public class MapVariableResolverFactory implements VariableResolverFactory {
    /**
     * Holds the instance of the variables.
     */
    private Map<String, Object> variables;

    private Map<String, VariableResolver> variableResolvers;
    private VariableResolverFactory nextFactory;


    public MapVariableResolverFactory(Map<String, Object> variables) {
        this.variables = variables;
    }

    public VariableResolver createVariable(String name, Object value) {
        if (nextFactory != null && nextFactory.isResolveable(name)) {
            VariableResolver vr = nextFactory.getVariableResolver(name);
            vr.setValue(value);
            return vr;
        }
        else {
            variables.put(name, value);
            return new MapVariableResolver(variables, name);
        }
    }

    public VariableResolverFactory getNextFactory() {
        return nextFactory;
    }

    public VariableResolverFactory setNextFactory(VariableResolverFactory resolverFactory) {
        return nextFactory = resolverFactory;
    }

    public VariableResolver getVariableResolver(String name) {
        if (isResolveable(name)) {
            if (variableResolvers != null && variableResolvers.containsKey(name)) return variableResolvers.get(name);
            else return nextFactory.getVariableResolver(name);
        }
        return null;

    }


    public boolean isResolveable(String name) {
        if (variableResolvers != null && variableResolvers.containsKey(name)) {
            return true;
        }
        else if (variables != null && variables.containsKey(name)) {
            if (variableResolvers == null) variableResolvers = new HashMap<String, VariableResolver>();
            variableResolvers.put(name, new MapVariableResolver(variables, name));
            return true;
        }
        else if (nextFactory != null) {
            return nextFactory.isResolveable(name);
        }
        return false;
    }

    public void pack() {
        if (variables != null) {
            if (variableResolvers == null) variableResolvers = new HashMap<String, VariableResolver>();
            for (String s : variables.keySet()) {
                variableResolvers.put(s, new MapVariableResolver(variables, s));
            }
        }
    }


    public boolean isTarget(String name) {
        return variableResolvers.containsKey(name);
    }
}