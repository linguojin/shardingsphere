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

package org.apache.shardingsphere.shadow.route.engine;

import org.apache.shardingsphere.api.config.shadow.ShadowRuleConfiguration;
import org.apache.shardingsphere.core.rule.ShadowRule;
import org.apache.shardingsphere.sql.parser.binder.segment.insert.values.InsertValueContext;
import org.apache.shardingsphere.sql.parser.binder.statement.ddl.CreateTableStatementContext;
import org.apache.shardingsphere.sql.parser.binder.statement.dml.InsertStatementContext;
import org.apache.shardingsphere.sql.parser.sql.statement.ddl.CreateTableStatement;
import org.apache.shardingsphere.sql.parser.sql.statement.dml.InsertStatement;
import org.apache.shardingsphere.underlying.common.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.underlying.common.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.underlying.route.context.RouteContext;
import org.apache.shardingsphere.underlying.route.context.RouteMapper;
import org.apache.shardingsphere.underlying.route.context.RouteResult;
import org.apache.shardingsphere.underlying.route.context.RouteUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Iterator;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ShadowRouteDecoratorTest {
    
    private static final String SHADOW_COLUMN = "is_shadow";
    
    private static final String ACTUAL_DATASOURCE = "ds";
    
    private static final String SHADOW_DATASOURCE = "shadow_ds";
    
    @Mock
    private InsertStatementContext sqlStatementContext;
    
    @Mock
    private InsertStatement insertStatement;
    
    @Mock
    private CreateTableStatementContext createTableStatementContext;
    
    @Mock
    private CreateTableStatement createTableStatement;
    
    private ShadowRouteDecorator routeDecorator;
    
    private ShadowRule shadowRule;
    
    @Before
    public void setUp() {
        routeDecorator = new ShadowRouteDecorator();
        ShadowRuleConfiguration shadowRuleConfiguration = new ShadowRuleConfiguration();
        shadowRuleConfiguration.setColumn(SHADOW_COLUMN);
        shadowRuleConfiguration.setShadowMappings(Collections.singletonMap(ACTUAL_DATASOURCE, SHADOW_DATASOURCE));
        shadowRule = new ShadowRule(shadowRuleConfiguration);
        
    }
    
    @Test
    public void assertDecorateToShadowWithOutRouteUnit() {
        RouteContext routeContext = mockSQLRouteContextForShadow();
        RouteContext actual = routeDecorator.decorate(routeContext, mock(ShardingSphereMetaData.class), shadowRule, new ConfigurationProperties(new Properties()));
        Iterator<String> routedDataSourceNames = actual.getRouteResult().getActualDataSourceNames().iterator();
        assertThat(routedDataSourceNames.next(), is(SHADOW_DATASOURCE));
    }
    
    @Test
    public void assertDecorateToActualWithOutRouteUnit() {
        RouteContext routeContext = mockSQLRouteContext();
        RouteContext actual = routeDecorator.decorate(routeContext, mock(ShardingSphereMetaData.class), shadowRule, new ConfigurationProperties(new Properties()));
        Iterator<String> routedDataSourceNames = actual.getRouteResult().getActualDataSourceNames().iterator();
        assertThat(routedDataSourceNames.next(), is(ACTUAL_DATASOURCE));
    }
    
    @Test
    public void assertNonDMLStatementWithOutRouteUnit() {
        RouteContext routeContext = mockNonDMLSQLRouteContext();
        RouteContext actual = routeDecorator.decorate(routeContext, mock(ShardingSphereMetaData.class), shadowRule, new ConfigurationProperties(new Properties()));
        assertThat(actual.getRouteResult().getRouteUnits().size(), is(2));
        assertThat(actual.getRouteResult().getActualDataSourceNames().contains(SHADOW_DATASOURCE), is(true));
        assertThat(actual.getRouteResult().getActualDataSourceNames().contains(ACTUAL_DATASOURCE), is(true));
    }
    
    @Test
    public void assertDecorateToShadowWithRouteUnit() {
        RouteContext routeContext = mockSQLRouteContextForShadow();
        routeContext.getRouteResult().getRouteUnits().add(mockRouteUnit());
        RouteContext actual = routeDecorator.decorate(routeContext, mock(ShardingSphereMetaData.class), shadowRule, new ConfigurationProperties(new Properties()));
        assertThat(actual.getRouteResult().getRouteUnits().size(), is(1));
        assertThat(actual.getRouteResult().getActualDataSourceNames().contains(SHADOW_DATASOURCE), is(true));
    }
    
    @Test
    public void assertDecorateToActualWithRouteUnit() {
        RouteContext routeContext = mockSQLRouteContext();
        routeContext.getRouteResult().getRouteUnits().add(mockRouteUnit());
        RouteContext actual = routeDecorator.decorate(routeContext, mock(ShardingSphereMetaData.class), shadowRule, new ConfigurationProperties(new Properties()));
        Iterator<String> routedDataSourceNames = actual.getRouteResult().getActualDataSourceNames().iterator();
        assertThat(routedDataSourceNames.next(), is(ACTUAL_DATASOURCE));
    }
    
    @Test
    public void assertNonDMLStatementWithRouteUnit() {
        RouteContext routeContext = mockNonDMLSQLRouteContext();
        routeContext.getRouteResult().getRouteUnits().add(mockRouteUnit());
        RouteContext actual = routeDecorator.decorate(routeContext, mock(ShardingSphereMetaData.class), shadowRule, new ConfigurationProperties(new Properties()));
        assertThat(actual.getRouteResult().getRouteUnits().size(), is(2));
        assertThat(actual.getRouteResult().getActualDataSourceNames().contains(SHADOW_DATASOURCE), is(true));
        assertThat(actual.getRouteResult().getActualDataSourceNames().contains(ACTUAL_DATASOURCE), is(true));
    }
    
    private RouteContext mockSQLRouteContextForShadow() {
        when(sqlStatementContext.getSqlStatement()).thenReturn(insertStatement);
        when(sqlStatementContext.getDescendingColumnNames()).thenReturn(Collections.singletonList(SHADOW_COLUMN).iterator());
        when(sqlStatementContext.getColumnNames()).thenReturn(Collections.singletonList(SHADOW_COLUMN));
        InsertValueContext insertValueContext = mock(InsertValueContext.class);
        when(insertValueContext.getValue(0)).thenReturn(true);
        when(sqlStatementContext.getInsertValueContexts()).thenReturn(Collections.singletonList(insertValueContext));
        return new RouteContext(sqlStatementContext, Collections.emptyList(), new RouteResult());
    }
    
    private RouteContext mockSQLRouteContext() {
        when(sqlStatementContext.getSqlStatement()).thenReturn(insertStatement);
        return new RouteContext(sqlStatementContext, Collections.emptyList(), new RouteResult());
    }
    
    private RouteContext mockNonDMLSQLRouteContext() {
        when(createTableStatementContext.getSqlStatement()).thenReturn(createTableStatement);
        return new RouteContext(createTableStatementContext, Collections.emptyList(), new RouteResult());
    }

    private RouteUnit mockRouteUnit() {
        return new RouteUnit(new RouteMapper(ACTUAL_DATASOURCE, ACTUAL_DATASOURCE), Collections.singletonList(new RouteMapper("table", "table_0")));
    }
}
