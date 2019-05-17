/*
 * <<
 * Davinci
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * >>
 */

package edp.davinci.core.utils;

import com.alibaba.druid.util.StringUtils;
import com.sun.tools.javac.util.ListBuffer;
import edp.core.exception.ServerException;
import edp.core.utils.SqlUtils;
import edp.davinci.core.common.Constants;
import edp.davinci.core.enums.SqlOperatorEnum;
import edp.davinci.core.enums.SqlVariableTypeEnum;
import edp.davinci.core.enums.SqlVariableValueTypeEnum;
import edp.davinci.core.model.SqlEntity;
import edp.davinci.model.SqlVariable;
import edp.davinci.model.SqlVariableChannel;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.stringtemplate.v4.ST;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static edp.core.consts.Consts.*;
import static edp.davinci.core.common.Constants.*;

@Slf4j
@Component
public class SqlParseUtils {

    private static final String SELECT = "select";

    private static final String WITH = "with";

    @Autowired
    private DacChannelUtil dacChannelUtil;

    /**
     * 解析sql
     *
     * @param sqlStr
     * @return
     */
    public SqlEntity parseSql(String sqlStr, List<SqlVariable> variables, String sqlTempDelimiter) throws ServerException {
        if (StringUtils.isEmpty(sqlStr.trim())) {
            return null;
        }

        sqlStr = SqlUtils.filterAnnotate(sqlStr);
        sqlStr = sqlStr.replaceAll(newLineChar, space).trim();

        char delimiter = getSqlTempDelimiter(sqlTempDelimiter);

        Pattern p = Pattern.compile(getReg(REG_SQL_PLACEHOLDER, delimiter));
        Matcher matcher = p.matcher(sqlStr);

        if (!matcher.find()) {
            return new SqlEntity(sqlStr, null, null);
        }

        Map<String, Object> queryParamMap = new ConcurrentHashMap<>();
        Map<String, List<String>> authParamMap = new ConcurrentHashMap<>();

        //解析参数
        if (null != variables && variables.size() > 0) {
            ExecutorService executorService = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch countDownLatch = new CountDownLatch(variables.size());
                variables.forEach(variable -> executorService.execute(() -> {
                    SqlVariableTypeEnum typeEnum = SqlVariableTypeEnum.typeOf(variable.getType());
                    if (null != typeEnum) {
                        switch (typeEnum) {
                            case QUERYVAR:
                                queryParamMap.put(variable.getName().trim(), SqlVariableValueTypeEnum.getValue(variable.getValueType(), variable.getDefaultValues()));
                                break;
                            case AUTHVARE:
                                String k = String.join("", String.valueOf(delimiter), variable.getName().trim(), String.valueOf(delimiter));
                                if (null != variable) {
                                    List<String> v = getAuthVarValue(variable, null);
                                    if (null != v && v.size() > 0) {
                                        authParamMap.put(k, v);
                                    }
                                }
                                break;
                        }
                    }
                    countDownLatch.countDown();

                }));
                countDownLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                executorService.shutdown();
            }
        }
        return new SqlEntity(sqlStr, queryParamMap, authParamMap);
    }


    public List<String> getAuthVarValue(SqlVariable variable, String email) {
        SqlVariableChannel channel = variable.getChannel();
        if (null == channel) {
            return SqlVariableValueTypeEnum.getValue(variable.getValueType(), variable.getDefaultValues());
        } else if (DacChannelUtil.dacMap.containsKey(channel.getName())) {
            List<Object> data = dacChannelUtil.getData(channel.getName(), channel.getBizId().toString(), email);
            if (null != data) {
                return SqlVariableValueTypeEnum.getValue(variable.getValueType(), data);
            }
        }
        return new ArrayList<>();
    }

    /**
     * 替换参数
     *
     * @param sql
     * @param queryParamMap
     * @param authParamMap
     * @param sqlTempDelimiter
     * @return
     */
    public String replaceParams(String sql, Map<String, Object> queryParamMap, Map<String, List<String>> authParamMap, String sqlTempDelimiter) {
        if (StringUtils.isEmpty(sql)) {
            return null;
        }

        char delimiter = getSqlTempDelimiter(sqlTempDelimiter);

        //替换auth@var
        Pattern p = Pattern.compile(getReg(REG_AUTHVAR, delimiter));
        Matcher matcher = p.matcher(sql);

        Set<String> expSet = new HashSet<>();
        while (matcher.find()) {
            expSet.add(matcher.group());
        }
        if (expSet.size() > 0) {
            Map<String, String> parsedMap = getParsedExpression(expSet, authParamMap, delimiter);
            for (String key : parsedMap.keySet()) {
                if (sql.indexOf(key) > -1) {
                    sql = sql.replace(key, parsedMap.get(key));
                }
            }
        }

        ST st = new ST(sql, delimiter, delimiter);
        //替换query@var
        if (null != queryParamMap && queryParamMap.size() > 0) {
            for (String key : queryParamMap.keySet()) {
                st.add(key, queryParamMap.get(key));
            }
        }
        sql = st.render();
        return sql;
    }


    public List<String> getSqls(String sql, boolean isQuery) {
        sql = sql.trim();

        if (StringUtils.isEmpty(sql)) {
            return null;
        }

        if (sql.startsWith(semicolon)) {
            sql = sql.substring(1);
        }

        if (sql.endsWith(semicolon)) {
            sql = sql.substring(0, sql.length() - 1);
        }

        List<String> list = null;

        String[] split = sql.split(semicolon);
        if (null != split && split.length > 0) {
            list = new ArrayList<>();
            for (String sqlStr : split) {
                sqlStr = sqlStr.trim();
                boolean select = sqlStr.toLowerCase().startsWith(SELECT) || sqlStr.toLowerCase().startsWith(WITH);
                if (isQuery) {
                    if (select) {
                        list.add(sqlStr);
                    } else {
                        continue;
                    }
                } else {
                    if (!select) {
                        list.add(sqlStr);
                    } else {
                        continue;
                    }
                }
            }
        }
        return list;
    }


    private static Map<String, String> getParsedExpression(Set<String> expSet, Map<String, List<String>> authParamMap, char sqlTempDelimiter) {
        Iterator<String> iterator = expSet.iterator();
        Map<String, String> map = new HashMap<>();
        while (iterator.hasNext()) {
            String exp = iterator.next().trim();
            try {
                map.put(exp, getAuthVarExpression(exp, authParamMap, sqlTempDelimiter));
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
        }
        if (map.size() > 0) {
            return map;
        } else {
            return null;
        }
    }

    private static String getAuthVarExpression(String srcExpression, Map<String, List<String>> authParamMap, char sqlTempDelimiter) throws Exception {

        if (null == authParamMap) {
            return "1=1";
        }

        String originExpression = "";
        if (!StringUtils.isEmpty(srcExpression)) {
            srcExpression = srcExpression.trim();
            if (srcExpression.startsWith(parenthesesStart) && srcExpression.endsWith(parenthesesEnd)) {
                srcExpression = srcExpression.substring(1, srcExpression.length() - 1);
            }

            String sql = String.format(Constants.SELECT_EXEPRESSION, srcExpression);
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            Expression where = plainSelect.getWhere();

            ListBuffer<Map<SqlOperatorEnum, List<String>>> listBuffer = new ListBuffer<>();
            where.accept(SqlOperatorEnum.getVisitor(listBuffer));
            Map<SqlOperatorEnum, List<String>> operatorMap = listBuffer.toList().head;

            String delimiter = String.valueOf(sqlTempDelimiter);

            for (SqlOperatorEnum sqlOperator : operatorMap.keySet()) {
                List<String> expList = operatorMap.get(sqlOperator);
                if (null != expList && expList.size() > 0) {
                    String left = operatorMap.get(sqlOperator).get(0);
                    String right = operatorMap.get(sqlOperator).get(expList.size() - 1);
                    if (right.startsWith(delimiter) && right.endsWith(delimiter)) {
                        right = right.substring(1, right.length() - 1);
                    }
                    if (authParamMap.containsKey(right)) {
                        List<String> list = authParamMap.get(right);
                        if (null != list && list.size() > 0) {
                            StringBuilder expBuilder = new StringBuilder();
                            if (list.size() == 1) {
                                if (!StringUtils.isEmpty(list.get(0))) {
                                    switch (sqlOperator) {
                                        case IN:
                                            expBuilder
                                                    .append(left).append(space)
                                                    .append(SqlOperatorEnum.IN.getValue()).append(space)
                                                    .append(list.stream().collect(Collectors.joining(comma, parenthesesStart, parenthesesEnd)));
                                            break;
                                        default:
                                            if (list.get(0).split(",").length > 1) {
                                                expBuilder
                                                        .append(left).append(space)
                                                        .append(SqlOperatorEnum.IN.getValue()).append(space)
                                                        .append(list.stream().collect(Collectors.joining(comma, parenthesesStart, parenthesesEnd)));
                                            } else {
                                                expBuilder
                                                        .append(left).append(space)
                                                        .append(sqlOperator.getValue()).append(space).append(list.get(0));
                                            }
                                            break;
                                    }
                                } else {
                                    return "1=1";
                                }
                            } else {
                                switch (sqlOperator) {
                                    case IN:
                                    case EQUALSTO:
                                        expBuilder
                                                .append(left).append(space)
                                                .append(SqlOperatorEnum.IN.getValue()).append(space)
                                                .append(list.stream().collect(Collectors.joining(comma, parenthesesStart, parenthesesEnd)));
                                        break;

                                    case NOTEQUALSTO:
                                        expBuilder
                                                .append(left).append(space)
                                                .append(SqlOperatorEnum.NoTIN.getValue()).append(space)
                                                .append(list.stream().collect(Collectors.joining(comma, parenthesesStart, parenthesesEnd)));
                                        break;

                                    case BETWEEN:
                                    case GREATERTHAN:
                                    case GREATERTHANEQUALS:
                                    case MINORTHAN:
                                    case MINORTHANEQUALS:
                                        expBuilder.append(list.stream()
                                                .map(x -> space + left + space + SqlOperatorEnum.BETWEEN.getValue() + space + x + space)
                                                .collect(Collectors.joining("or", parenthesesStart, parenthesesEnd)));
                                        break;

                                    default:
                                        expBuilder.append(originExpression);
                                        break;
                                }
                            }
                            return expBuilder.toString();
                        } else {
                            return "1=1";
                        }
                    } else {
                        return "1=0";
                    }
                }
            }
        }
        return originExpression;
    }
}
