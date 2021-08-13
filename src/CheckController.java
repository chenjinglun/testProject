package com.atguigu.springcloud.controller;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目名称:
 * 包:
 * 类名称:
 * 类描述:
 * 创建人: chenjinglun
 * 创建时间: 2021/7/30 10:32
 * 版本:最完整版本
 * @author chenjinglun
 */
@RestController
@CrossOrigin
public class CheckController {

    /**
     * xml检查
     * @param requestVO checkPath 检查路径，prefix前缀表名
     * @return Map
     * @throws IOException
     */
    @PostMapping("/checkXml")
    public Map checkXml(@RequestBody RequestVO requestVO) throws IOException {
        //返回结果定义
        Map returnResult = new HashMap(1024);
        if (requestVO.getPrefix() == null){
            returnResult.put("err","前缀表名不能为空");
            return returnResult;
        }
        //获取检查服务的所有xml文件
        File[] xmlList = getXmlList(new File(requestVO.getCheckPath()));
        FileInputStream fileInputStream;
        //存放冲突表的集合
        Set<Map> exitTableSet = new HashSet<>();
        for (File xmlFileName: xmlList) {
            File file = new File(String.valueOf(xmlFileName));
            fileInputStream = new FileInputStream(file);
            byte[] textByte = new byte[(int) file.length()];
            //读取xml文本内容
            fileInputStream.read(textByte);
            String xmlText = new String (textByte);
            //将xml文本内容把空格换行注释等去掉变成一个字符串
            xmlText = xmlText.replaceAll("(\\r\\n|\\n|\\\\n|\\s|<!--|-->)", "");
            //获取所有的select节点
            List matchSelectNode = matcher("<selectid=.*?</select>",xmlText);
            //获取所有的delete节点
            List matchDeleteNode = matcher("<deleteid=.*?</delete>",xmlText);
            //获取所有的insert节点
            List matchInsertNode = matcher("<insertid=.*?</insert>",xmlText);
            //获取所有的update节点
            List matchUpdateNode = matcher("<updateid=.*?</update>",xmlText);
            //获取所有的sql节点
            List matchSqlNode = matcher("<sqlid=.*?</sql>",xmlText);
            matchSelectNode.addAll(matchDeleteNode);
            matchSelectNode.addAll(matchInsertNode);
            matchSelectNode.addAll(matchUpdateNode);
            matchSelectNode.addAll(matchSqlNode);
            for (int i = 0; i < matchSelectNode.size(); i++) {
                //判断标志
                Boolean flag = false;
                Boolean flag2 = false;
                Boolean flag3 = false;
                //遍历节点信息
                String nodeStr = (String) matchSelectNode.get(i);
                List matchFirst = matcher("from.*where",nodeStr);
                //处理子查询作为表检索
                if (matchFirst.size() != 0 && matchFirst.get(0).toString().startsWith("from(")){
                    //检索判断复用查询语句
                    if (matchFirst.get(0).toString().startsWith("from(<include")){
                        List matchSecond = matcher("from\\(<include.*?where|from.*?where",matchFirst.get(0).toString());
                        for (int j = 0; j < matchSecond.size(); j++) {
                            if (matchSecond.get(j).toString().startsWith("from(<include")){
                                matchSecond.remove(j);
                                j--;
                                flag3 = true;
                            }
                        }
                        matchFirst = matchSecond;
                        //普通select语句的表名截取，并校验
                        List trimResult = trimString(matchFirst,4,7);
                        adjust(trimResult,requestVO.getPrefix(),exitTableSet,xmlFileName);
                        continue;
                    }
                    //查询如正则表达式所示单独处理
                    if (matchFirst.size() != 0 && flag3 == false){
                        List matchThird = matcher("select.*?\\(s|elect.*?where",matchFirst.get(0).toString());
                        adjust2(matchThird,requestVO.getPrefix(),exitTableSet,xmlFileName);
                        flag2 = true;
                    }
                }
                //查询处理groupBy,orderBy,having语句
                if (matchFirst.size() == 0){
                    List matchFourth = matcher("from.*?group|from.*?order|from.*?havin",nodeStr);
                    if (matchFourth.size() != 0){
                        List trimResult = trimString(matchFourth,4,7);
                        adjust(trimResult,requestVO.getPrefix(),exitTableSet,xmlFileName);
                        flag = true;
                    }
                }
                //查询处理左 右 内连接等场景情况
                if (flag == false && flag2 == false){
                    List matchFifth = matcher("from.*?innerjoin.*?on|from.*?leftj|from.*?where|from.*?groupby|from.*?orderby|from.*?having|from.*?right",nodeStr);
                    adjust2(matchFifth,requestVO.getPrefix(),exitTableSet,xmlFileName);
                    //单独处理<sql节点的查询场景处理
                    Boolean target = adjust3(matchFifth,requestVO.getPrefix(),exitTableSet,xmlFileName);
                    //这里的作用是区分<sql 节点内容是不是 select查询，若是处理清除掉，剩下的后续方法处理
                    if (!target){
                        for (int j = 0; j < matchSqlNode.size(); j++) {
                            if (matchSqlNode.get(j).equals(nodeStr)){
                                matchSqlNode.remove(j);
                            }
                        }
                    }
                    if (target){
                        List trimResult = trimString(matchFifth,4,7);
                        adjust(trimResult,requestVO.getPrefix(),exitTableSet,xmlFileName);
                        if (trimResult.size() == 0 && flag == false){
                            //匹配from后的表是否存在多表判断
                            List matchSixth = matcher("from.*?where",nodeStr);
                            List result =  trimString(matchSixth,4,5);
                            adjust(result,requestVO.getPrefix(),exitTableSet,xmlFileName);
                            //判断是否from后无where
                            if (matchSixth.size() == 0){
                                List matchSeventh = matcher("from.*<",nodeStr);
                                List result2 = trimString(matchSeventh,4,3);
                                adjust(result2,requestVO.getPrefix(),exitTableSet,xmlFileName);
                            }
                        }
                    }
                }
                //匹配insert规则的节点，进行处理 所有信息整理皆可优化处理,降低程序运行时间
                List matchInsertTable = matcher("insertinto.*?\\(",nodeStr);
                List resultInsert = trimString(matchInsertTable,10,1);
                adjust(resultInsert,requestVO.getPrefix(),exitTableSet,xmlFileName);
                //匹配update规则的节点，进行处理
                List matchUpdateTable = matcher("\\>update.*?set",nodeStr);
                List resultUpdate = trimString(matchUpdateTable,7,3);
                adjust(resultUpdate,requestVO.getPrefix(),exitTableSet,xmlFileName);
            }
            //单独处理<sql...></sql>节点的join表场景处理
            for (int i = 0; i < matchSqlNode.size(); i++) {
                String matchingStr = (String) matchSqlNode.get(i);
                //匹配sql规则的
                List matchEighth = matcher("rightjoin.*?on|leftjoin.*?on|innerjoin.*?on|union.*",matchingStr);
                List result = trimString(matchEighth,8,4);
                adjust(result,requestVO.getPrefix(),exitTableSet,xmlFileName);
            }
        }
        if (exitTableSet.size() > 0){
            if (requestVO.getCheckPath() != null && !StringUtils.isEmpty(requestVO.getCheckPath())){
                returnResult.put("serviceName",requestVO.getCheckPath());
            }else{
                returnResult.put("serviceName","单体服务");
            }
            returnResult.put("code",500);
            returnResult.put("checkName",requestVO.getPrefix());
            returnResult.put("checkTable",exitTableSet);
        }else{
            returnResult.put("code",200);
            returnResult.put("checkName",requestVO.getPrefix());
            returnResult.put("serviceName",requestVO.getCheckPath());
        }

        return returnResult;
    }

    /**
     *  //获取该文件夹下所有的文件
     * @param file
     * @return
     */
    public File[] getXmlList(File file){
        //获取该文件夹下所有的文件
        File[] fileArray= file.listFiles();
        File fileName = null;
        for(int i =0;i<fileArray.length;i++){
            fileName = fileArray[i];
            //判断此文件是否存在
            if(fileName.isDirectory()){
                System.out.println("【目录："+fileName.getName()+"】");
            }else{
                System.out.println(fileName.getName());
            }
        }
        return fileArray;
    }

    /**
     *正则表达式匹配，返回匹配的内容
     * @param patten
     * @param matcherTest
     * @return List
     */
    public List matcher(String patten,String matcherTest){
        Pattern pattern = Pattern.compile(patten);
        Matcher matcher = pattern.matcher(matcherTest);
        List matchList = new ArrayList<>();
        while (matcher.find()) {
            matchList.add(matcher.group());
        }
        return matchList;
    }

    /**
     *字符串窃取，截取表名
     * @param trimString
     * @param start
     * @param end
     * @return
     */
    public List trimString(List trimString,int start,int end){
        List newList = new ArrayList();
        for (int i = 0; i < trimString.size(); i++) {
            String newString = trimString.get(i).toString();
            newString = newString.substring(start,newString.length()-end);
            String[] nameArr = newString.split(",");
            for (String tableName: nameArr) {
                newList.add(tableName);
            }
        }
        return newList;
    }

    /**
     *与前缀表名进行匹配，若非前缀表名的表进行记录
     * @param result
     * @param prefix
     * @param exitTable
     * @param actionName
     */
    public void adjust(List result,String prefix,Set exitTable,File actionName){
        for (int j = 0; j < result.size(); j++) {
            Map map = new HashMap(1024);
            int len = prefix.length();
            //约定前缀长度必须小于等于表名
            String tableName = result.get(j).toString().substring(0,len);
            if (!prefix.equals(tableName)){
                map.put("冲突表名:"+result.get(j),"所在xml名:"+actionName.getName());
                exitTable.add(map);
            }
        }
    }

    /**
     *根据不同出路场景所选择的 判断与表前缀是否吻合的校验方法2
     * @param result
     * @param prefix
     * @param exitTable
     * @param actionName
     */
    public void adjust2(List result,String prefix,Set exitTable,File actionName){
        for (int i = 0; i < result.size(); i++) {
            List matchResult = matcher("from.*?where",result.get(i).toString());
            for (int j = 0; j < matchResult.size(); j++) {
                if (checkString((String) matchResult.get(j))){
                    List resultUpdate =  trimString(matchResult,4,10);
                    adjust(resultUpdate,prefix,exitTable,actionName);
                }else {
                    List resultUpdate =  trimString(matchResult,4,7);
                    adjust(resultUpdate,prefix,exitTable,actionName);
                }
            }
        }
    }

    /**
     * 根据不同出路场景所选择的 判断与表前缀是否吻合的校验方法3
     * inner join 表名截取
     * @param result
     * @param prefix
     * @param exitTable
     * @param actionName
     * @return
     */
    public Boolean adjust3(List result,String prefix,Set exitTable,File actionName){
        for (int i = 0; i < result.size(); i++) {
            List matchResult = matcher("from.*?innerjoin.*?on",result.get(i).toString());
            List list = new ArrayList();
            for (int j = 0; j < matchResult.size(); j++) {
                String str = matchResult.get(j).toString();
                String content = str.substring(str.indexOf("from"),str.indexOf("innerjoin")).substring("from".length());
                content = content.substring(0,content.length()-2);
                String content2 = str.substring(str.indexOf("innerjoin"),str.indexOf("on")).substring("innerjoin".length());
                content2 = content2.substring(0,content.length()-1);
                list.add(content);
                list.add(content2);
                adjust(list,prefix,exitTable,actionName);
                if (list.size() != 0){
                    return false;
                }
            }
        }
        return true;
    }
    /**
     *场景检查是否含有 ) 的进行表名截取判断
     * @param str
     * @return
     */
    public Boolean checkString(String str){
        char[] check = str.toCharArray();
        for (char ch:check) {
            if (ch == ')'){
                return true;
            }
        }
        return false;
    }
}
