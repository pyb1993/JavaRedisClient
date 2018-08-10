package Util;

/*
 * 用来wrap requestId 和 真正的数据:resp
 *
 * */
public class Resp {
    String reqId;
    Object resp;

    public  Resp(String id, Object resp){
        this.reqId = id;
        this.resp = resp;
    }

    public String getReqId() {
        return reqId;
    }

    public Object getResp(){
        return  resp;
    }
}