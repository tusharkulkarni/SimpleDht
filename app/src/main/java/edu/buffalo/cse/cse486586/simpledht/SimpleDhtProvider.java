package edu.buffalo.cse.cse486586.simpledht;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;
import java.util.Map;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SimpleDhtProvider extends ContentProvider implements Constants {
    static final String TAG = SimpleDhtProvider.class.getSimpleName();
    //private static final  Semaphore querySemaphore = new Semaphore(1);
    Context context;
    private static Cursor globalCursor = null;
    private static int globalDeleteCount = -1;
    static NodeDetails myNodeDetails;
    static List<NodeDetails> chordNodeList;
    //boolean isStandAloneMode = false;

    //nodeId will contain value like 5554
    private String getMyNodeId() {
        TelephonyManager tel = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String nodeId = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        return nodeId;
    }

    //initialize node Details for this avd
    private void init() {
        myNodeDetails = new NodeDetails();
        try {
            //port will store value of port used for connection
            //eg: port = 5554*2 = 11108
            String port = String.valueOf(Integer.parseInt(getMyNodeId()) * 2);
            //nodeIdHash will store hash of nodeId =>
            // eg: nodeIdHash = hashgen(5554)
            String nodeIdHash = genHash(getMyNodeId());
            myNodeDetails.setPort(port);
            myNodeDetails.setNodeIdHash(nodeIdHash);
            myNodeDetails.setPredecessorPort(port);
            myNodeDetails.setSuccessorPort(port);
            myNodeDetails.setSuccessorNodeIdHash(nodeIdHash);
            myNodeDetails.setPredecessorNodeIdHash(nodeIdHash);
            myNodeDetails.setFirstNode(true);

            if (getMyNodeId().equalsIgnoreCase(masterPort)) {
                chordNodeList = new ArrayList<NodeDetails>();
                chordNodeList.add(myNodeDetails);
            }

        } catch (Exception e) {
            Log.e(TAG,"**************************Exception in init()**********************");
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCreate() {
        init();

        //create server task to accept requests from other nodes
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT, 25);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket");
            e.printStackTrace();
            return false;
        }

        //when node joins request node 5554 (port 11108) to get node details

        Message message = new Message();
        message.setMessageType(MessageType.NodeJoin);
        message.setNodeDetails(myNodeDetails);
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, message);

        return true;
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {
        Uri mUri = buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");

        private Uri buildUri(String scheme, String authority) {
            Uri.Builder uriBuilder = new Uri.Builder();
            uriBuilder.authority(authority);
            uriBuilder.scheme(scheme);
            return uriBuilder.build();
        }

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];
            ObjectInputStream inputStream;
            ObjectOutputStream outputStream;
            Socket socket;
            Integer deleteCount = -1;

            Message message = new Message();
            while(true) {
                try {
                    socket = serverSocket.accept();
                    //Initialize input and output stream for full duplex sockect connection
                    inputStream = new ObjectInputStream(socket.getInputStream());
                    outputStream = new ObjectOutputStream(socket.getOutputStream());
                    message = (Message) inputStream.readObject();
                    switch (message.getMessageType()) {
                        case NodeJoin:
                            message.setNodeDetails(nodeJoin(message));
                            nodeJoinNotification();
                            break;
                        case NodeJoinNotification:
                            updateMyNodeDetails(message);
                            break;
                        case Insert:
                            insertValues(message);
                            break;
                        case SingleQuery:
                            message = singleQuery(message);
                            outputStream.writeObject(message);
                            outputStream.flush();
                            break;
                        case GlobalDump:
                            List<Message> messageList = getLocalDump();
                            outputStream.writeObject(messageList);
                            outputStream.flush();
                            break;
                        case SingleDelete:
                            deleteCount = singleDelete(message);
                            outputStream.writeObject(deleteCount);
                            outputStream.flush();
                            break;
                        case GlobalDelete:
                            deleteCount = executeLocalDelete();
                            outputStream.writeObject(deleteCount);
                            outputStream.flush();
                            break;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private NodeDetails nodeJoin(Message message) {
            Log.e(TAG, "Node Join started");

            NodeDetails newNode = message.getNodeDetails();
            if(newNode.getPort().equalsIgnoreCase(myNodeDetails.getPort())){
                Log.e(TAG, "New Node is 11108" );
                return myNodeDetails;
            }
            String newPort = newNode.getPort();
            String newNodeIdHash = newNode.getNodeIdHash();
            NodeDetails prevNode = new NodeDetails();
            NodeDetails nextNode = new NodeDetails();
            Log.e(TAG, "New Node is : " + newPort);

            for (int i = 0; i <= chordNodeList.size(); i++) {
                if(i == 0){
                    prevNode = chordNodeList.get(chordNodeList.size()-1);
                }else{
                    prevNode = chordNodeList.get(i-1);
                }

                if (i == chordNodeList.size()) {
                    nextNode = chordNodeList.get(0);
                    break;
                }else {
                    nextNode = chordNodeList.get(i);
                }

                if (newNode.getNodeIdHash().compareTo(nextNode.getNodeIdHash()) < 0) {
                    break;
                }
            }
            newNode.setSuccessorPort(prevNode.getSuccessorPort());
            newNode.setPredecessorPort(nextNode.getPredecessorPort());
            newNode.setSuccessorNodeIdHash(prevNode.getSuccessorNodeIdHash());
            newNode.setPredecessorNodeIdHash(nextNode.getPredecessorNodeIdHash());
            prevNode.setSuccessorPort(newPort);
            prevNode.setSuccessorNodeIdHash(newNodeIdHash);
            nextNode.setPredecessorPort(newPort);
            nextNode.setPredecessorNodeIdHash(newNodeIdHash);
            chordNodeList.add(newNode);
            Collections.sort(chordNodeList, new NodeComparator());
            for (NodeDetails node:chordNodeList) {
                node.setFirstNode(false);
            }
            chordNodeList.get(0).setFirstNode(true);
            Log.e(TAG, "Node list : " + chordNodeList.toString());
            return newNode;
        }

        private void nodeJoinNotification(){
            Message message;
            ObjectOutputStream outputStream;
            Socket socket;
            for (NodeDetails node: chordNodeList) {
                try {
                    message = new Message();
                    message.setMessageType(MessageType.NodeJoinNotification);
                    message.setNodeDetails(node);
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(node.getPort()));
                    outputStream = new ObjectOutputStream(socket.getOutputStream());
                    outputStream.writeObject(message);
                    outputStream.flush();
                }catch(Exception e){
                    Log.e(TAG,"********************** Exception in nodeJoinNotification");
                    e.printStackTrace();
                }
            }
        }

        private void updateMyNodeDetails(Message message){
            myNodeDetails = message.getNodeDetails();
            Log.e(TAG,"myNodeDetails : " + myNodeDetails.toString());
        }

        private void insertValues(Message message){
            ContentResolver contentResolver = getContext().getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(KEY_FIELD, message.getKey());
            contentValues.put(VALUE_FIELD, message.getValue());
            contentResolver.insert(mUri, contentValues);
        }

        private Message singleQuery(Message message){
            ContentResolver contentResolver = getContext().getContentResolver();
            Cursor cursor = contentResolver.query(mUri, null, message.getKey(), null, null);
            cursor.moveToFirst();
            message.setKey(cursor.getString(cursor.getColumnIndex(KEY_FIELD)));
            message.setValue(cursor.getString(cursor.getColumnIndex(VALUE_FIELD)));
            Log.e(TAG, "****************************************");
            Log.e(TAG, "key from cursor : " + cursor.getString(cursor.getColumnIndex(KEY_FIELD)) + " value from cursor : " + cursor.getString(cursor.getColumnIndex(VALUE_FIELD)));
            Log.e(TAG, "key from message : " + message.getKey() + " value from message : " + message.getValue());
            Log.e(TAG, "****************************************");
            return message;
        }


        private List<Message> getLocalDump(){
            ContentResolver contentResolver = getContext().getContentResolver();
            Cursor cursor = contentResolver.query(mUri, null, "@", null, null);
            List<Message> messageList = new ArrayList<Message>();
            Message msg;
            Log.e(TAG, "Values retrieved and stored in cursor inside ServerTask() : " + DatabaseUtils.dumpCursorToString(cursor));
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                msg = new Message();
                msg.setKey(cursor.getString(cursor.getColumnIndex(KEY_FIELD)));
                msg.setValue(cursor.getString(cursor.getColumnIndex(VALUE_FIELD)));
                messageList.add(msg);
            }
            Log.e(TAG, "****************************************");
            Log.e(TAG, "messageList : " + messageList.toString());
            Log.e(TAG, "****************************************");

            return messageList;
        }

        private Integer singleDelete(Message message){
            int deleteCount = -1;
            ContentResolver contentResolver = getContext().getContentResolver();
            deleteCount = contentResolver.delete(mUri, message.getKey(), null);

            Log.e(TAG, "****************************************");

            Log.e(TAG, "DeleteCount in Server : " + deleteCount);
            Log.e(TAG, "****************************************");
            return deleteCount;
        }


        private Integer executeLocalDelete(){
            Integer deleteCount = -1;
            ContentResolver contentResolver = getContext().getContentResolver();
            deleteCount = contentResolver.delete(mUri,  "@",  null);

            Log.e(TAG, "****************************************");
            Log.e(TAG, "deleteCount : " + deleteCount);
            Log.e(TAG, "****************************************");

            return deleteCount;
        }

    }

    private class ClientTask extends AsyncTask<Message, Void, Void> {
        @Override
        protected Void doInBackground(Message... msg) {

            Message message = msg[0];

            switch (message.getMessageType()){
                case NodeJoin: requestNodeJoin(message);
                    break;
                case Insert:passInsertMessage(message);
                    break;
                case SingleQuery: singleQuery(message);
                    break;
                case GlobalDump: queryGlobalDump(message);
                    break;
                case SingleDelete: singleDelete(message);
                    break;
                case GlobalDelete: executeGlobalDelete(message);
                    break;
            }

            return null;
        }

        private void requestNodeJoin(Message message){
            ObjectOutputStream outputStream;
            Socket socket;
            try {
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT));
                outputStream = new ObjectOutputStream(socket.getOutputStream());

                socket.setSoTimeout(2000);
                Log.e(TAG, "Join Message to send is : " + message.toString());
                outputStream.writeObject(message);
                outputStream.flush();
            } catch (Exception e) {
                Log.e(TAG, "************************Exception in ClientTask().requestNodeJoin()******************");
                e.printStackTrace();
            }
        }

        private void passInsertMessage(Message message){
            ObjectOutputStream outputStream;
            Socket socket;
            String keyHash = "";
            try {
                keyHash = genHash(message.getKey());
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(myNodeDetails.getSuccessorPort()));
                outputStream = new ObjectOutputStream(socket.getOutputStream());
                Log.e(TAG, "Insert Message sent to : " + myNodeDetails.getSuccessorPort());
                Log.e(TAG, "Predecessor PortHash : " +myNodeDetails.getPredecessorNodeIdHash() +  " KeyHash : " + keyHash+ " MyPortHash : " + myNodeDetails.getNodeIdHash() );
                outputStream.writeObject(message);
                outputStream.flush();
            } catch (Exception e) {
                Log.e(TAG, "************************Exception in ClientTask().passInsertMessage()******************");
                e.printStackTrace();
            }
        }

        private void singleQuery(Message message){
            ObjectOutputStream outputStream;
            ObjectInputStream inputStream;
            Socket socket;
            String keyHash = "";
            String[] cursorColumns = new String[]{"key", "value"};
            MatrixCursor cursor = new MatrixCursor(cursorColumns);
            try {
                keyHash = genHash(message.getKey());
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(myNodeDetails.getSuccessorPort()));
                outputStream = new ObjectOutputStream(socket.getOutputStream());
                inputStream = new ObjectInputStream(socket.getInputStream());
                Log.e(TAG, "Predecessor PortHash : " + myNodeDetails.getPredecessorNodeIdHash() + " KeyHash : " + keyHash + " MyPortHash : " + myNodeDetails.getNodeIdHash());
                outputStream.writeObject(message);
                outputStream.flush();
                message = (Message)inputStream.readObject();
                Log.e(TAG, "****************************************");
                Log.e(TAG, "key from message : " + message.getKey() + " value from message : " + message.getValue());
                String[] row = new String[]{message.getKey(), message.getValue()};
                cursor.addRow(row);
                globalCursor = cursor;
                Log.e(TAG, "Values retrieved and stored in local cursor inside ClientTask(): " + DatabaseUtils.dumpCursorToString(cursor));
                Log.e(TAG, "Values retrieved and stored in global cursor inside ClientTask() : " + DatabaseUtils.dumpCursorToString(globalCursor));
                Log.e(TAG, "****************************************");
            } catch (Exception e) {
                Log.e(TAG, "************************Exception in ClientTask().singleQuery()******************");
                e.printStackTrace();
            }
            //querySemaphore.release();
        }


        private void singleDelete(Message message){
            ObjectOutputStream outputStream;
            ObjectInputStream inputStream;
            Socket socket;
            Integer deleteCount=0;


            try {
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(myNodeDetails.getSuccessorPort()));
                outputStream = new ObjectOutputStream(socket.getOutputStream());
                inputStream = new ObjectInputStream(socket.getInputStream());
                Log.e(TAG, "****************************************");
                Log.e(TAG, "key to be deleted retrieved from message : " + message.getKey());

                outputStream.writeObject(message);
                outputStream.flush();
                deleteCount = (Integer)inputStream.readObject();

                globalDeleteCount = deleteCount;
                Log.e(TAG, "DeleteCount inside ClientTask(): " + deleteCount);
                Log.e(TAG, "VGlobal Delete Count inside ClientTask() : " + globalDeleteCount);
                Log.e(TAG, "****************************************");
            } catch (Exception e) {
                Log.e(TAG, "************************Exception in ClientTask().singleQuery()******************");
                e.printStackTrace();
            }
        }


        private void queryGlobalDump(Message message){
            String[] cursorColumns = new String[]{"key", "value"};
            MatrixCursor cursor = new MatrixCursor(cursorColumns);
            ObjectOutputStream outputStream;
            ObjectInputStream inputStream;
            Socket socket;
            List<Message> messageList;
            for (String port: REMOTE_PORTS) {
                try {
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(port));
                    outputStream = new ObjectOutputStream(socket.getOutputStream());
                    inputStream = new ObjectInputStream(socket.getInputStream());

                    outputStream.writeObject(message);
                    outputStream.flush();
                    messageList = (List<Message>)inputStream.readObject();

                    for (Message msg: messageList) {
                        Log.e(TAG, "****************************************");
                        Log.e(TAG, "key from message : " + msg.getKey() + " value from message : " + msg.getValue());
                        String[] row = new String[]{msg.getKey(), msg.getValue()};
                        cursor.addRow(row);

                        Log.e(TAG, "Values retrieved and stored in local cursor inside ClientTask(): " + DatabaseUtils.dumpCursorToString(cursor));
                        Log.e(TAG, "Values retrieved and stored in global cursor inside ClientTask() : " + DatabaseUtils.dumpCursorToString(globalCursor));
                        Log.e(TAG, "****************************************");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "************************Exception in ClientTask().singleQuery()******************");
                    e.printStackTrace();
                }
            }
            globalCursor = cursor;
        }



        private void executeGlobalDelete(Message message){
            int deleteCount = 0;
            ObjectOutputStream outputStream;
            ObjectInputStream inputStream;
            Socket socket;

            for (String port: REMOTE_PORTS) {
                try {
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(port));
                    outputStream = new ObjectOutputStream(socket.getOutputStream());
                    inputStream = new ObjectInputStream(socket.getInputStream());

                    outputStream.writeObject(message);
                    outputStream.flush();
                    deleteCount += (Integer)inputStream.readObject();

                } catch (Exception e) {
                    Log.e(TAG, "************************Exception in ClientTask().singleQuery()******************");
                    e.printStackTrace();
                }
            }
            globalDeleteCount = deleteCount;
        }


    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        {
        /*
         * TODO: You need to implement this method. Note that values will have two columns (a key
         * column and a value column) and one row that contains the actual (key, value) pair to be
         * inserted.
         *
         * For actual storage, you can use any option. If you know how to use SQL, then you can use
         * SQLite. But this is not a requirement. You can use other storage options, such as the
         * internal storage option that we used in PA1. If you want to use that option, please
         * take a look at the code for PA1.
         */

            String key = values.getAsString(KEY_FIELD);
            String value = values.getAsString(VALUE_FIELD);
            String keyHash="";
            try{
                 keyHash = genHash(key);
            }catch (Exception e){
                Log.e(TAG,"***********************Exception in Insert************************");
                e.printStackTrace();
            }

            if(lookup(keyHash)){
                context = getContext();
                SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putString(key, value);
                editor.commit();
                Log.e(TAG, "Values inserted inside content provider : " + "key : " + key + "value : " + value);
            }else{
                Message message = new Message();
                message.setMessageType(MessageType.Insert);
                message.setNodeDetails(myNodeDetails);
                message.setKey(key);
                message.setValue(value);
                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, message);
            }

            return uri;
        }
    }


    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        context = getContext();
        String[] cursorColumns = new String[]{"key", "value"};
        Cursor returnCursor = null;
        MatrixCursor cursor = new MatrixCursor(cursorColumns);
        String key = selection;
        String keyHash = "";
        String value = "";
        try{
            keyHash = genHash(key);
        }catch (Exception e){
            Log.e(TAG,"***********************Exception in Query...genHash()************************");
            e.printStackTrace();
        }

        if (key.equalsIgnoreCase("*")) {
            returnCursor = getGlobalDump();
        } else if (key.equalsIgnoreCase("@")) {
            returnCursor = getLocalDump();
        } else {
            if(lookup(keyHash)){
                context = getContext();
                SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
                value = sharedPref.getString(key, "DEFAULT");
                String[] row = new String[]{key, value};
                cursor.addRow(row);
                returnCursor = cursor;
                Log.e(TAG, "Values retrieved : " + "key : " + key + "value : " + value);
            }else{
                Message message = new Message();
                message.setMessageType(MessageType.SingleQuery);
                message.setNodeDetails(myNodeDetails);
                message.setKey(key);
                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, message);

                /*try {
                    querySemaphore.acquire();
                }catch(Exception e){
                    Log.e(TAG,"***********************Exception in QuerySemaphore************************");
                    e.printStackTrace();
                }*/
                Log.e(TAG, "##################################################");
                Log.e(TAG, "Busy Waiting started...");
                while(globalCursor == null){
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Log.e(TAG,"***********************Exception in Query...sleep()************************");
                        e.printStackTrace();
                    }
                }
                returnCursor = globalCursor;
                Log.e(TAG, "Values retrieved and stored in global cursor inside query() before assigning null: " + DatabaseUtils.dumpCursorToString(globalCursor));
                globalCursor = null;
                Log.e(TAG,"Busy Waiting finished...");
                Log.e(TAG, "Values retrieved and stored in returnCursor inside query(): " + DatabaseUtils.dumpCursorToString(returnCursor));
                Log.e(TAG, "Values retrieved and stored in global cursor inside query() after assigning null: " + DatabaseUtils.dumpCursorToString(globalCursor));
                Log.e(TAG, "##################################################");
            }
        }
        Log.e(TAG, "Values retrieved and stored in cursor : " + DatabaseUtils.dumpCursorToString(returnCursor));
        return returnCursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        context = getContext();

        int deleteCount = 0;

        String key = selection;
        String keyHash = "";
        try{
            keyHash = genHash(key);
        }catch (Exception e){
            Log.e(TAG,"***********************Exception in Query...genHash()************************");
            e.printStackTrace();
        }

        if (key.equalsIgnoreCase("*")) {
            deleteCount = deleteGlobalDump();
        } else if (key.equalsIgnoreCase("@")) {
            deleteCount = deleteLocalDump();
        } else {
            if(lookup(keyHash)){
                context = getContext();
                SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.remove(key);
                editor.apply();
                deleteCount = 1;
                Log.e(TAG, "Deleted keys-value paris : " + deleteCount + " Deleted key : " + key );
            }else{
                Message message = new Message();
                message.setMessageType(MessageType.SingleDelete);
                message.setNodeDetails(myNodeDetails);
                message.setKey(key);
                new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, message);

                Log.e(TAG, "##################################################");
                Log.e(TAG, "Busy Waiting started...");
                while(globalDeleteCount < 0){
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Log.e(TAG,"***********************Exception in Query...sleep()************************");
                        e.printStackTrace();
                    }
                }
                Log.e(TAG,"Busy Waiting finished...");
                deleteCount = globalDeleteCount;
                Log.e(TAG, "globalDeleteCount before assigning 0: " + globalDeleteCount);
                globalDeleteCount = -1;
                Log.e(TAG, "DeleteCount : " + deleteCount);
                Log.e(TAG, "globalDeleteCount before assigning 0: " + globalDeleteCount);
                Log.e(TAG, "##################################################");
            }
        }
        Log.e(TAG, "DeleteCount : " + deleteCount);
        return deleteCount;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    private Cursor getGlobalDump() {
        Log.e(TAG, "Querying Global dump");
        Cursor returnCursor = null;
        Message message = new Message();
        message.setMessageType(MessageType.GlobalDump);
        message.setNodeDetails(myNodeDetails);
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, message);
        Log.e(TAG, "##################################################");
        Log.e(TAG, "Busy Waiting started...");

        while(globalCursor == null){
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Log.e(TAG,"***********************Exception in getGlobalDump...sleep()************************");
                e.printStackTrace();
            }
        }
        returnCursor = globalCursor;
        Log.e(TAG, "Values retrieved and stored in global cursor inside query() before assigning null: " + DatabaseUtils.dumpCursorToString(globalCursor));
        globalCursor = null;
        Log.e(TAG,"Busy Waiting finished...");
        Log.e(TAG, "Values retrieved and stored in returnCursor inside query(): " + DatabaseUtils.dumpCursorToString(returnCursor));
        Log.e(TAG, "Values retrieved and stored in global cursor inside query() after assigning null: " + DatabaseUtils.dumpCursorToString(globalCursor));
        Log.e(TAG, "##################################################");

        return returnCursor;
    }

    private MatrixCursor getLocalDump() {
        String[] cursorColumns = new String[]{"key", "value"};
        MatrixCursor cursor = new MatrixCursor(cursorColumns);
        Log.e(TAG, "Querying Local dump");
        Log.e(TAG, "Value dump from avd : " + getMyNodeId());
            SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
            Map<String, ?> keys = sharedPref.getAll();
            for (Map.Entry<String, ?> entry : keys.entrySet()) {
                Log.e(TAG, entry.getKey() + ": " + entry.getValue().toString());
                String[] row = new String[]{entry.getKey(), entry.getValue().toString()};
                cursor.addRow(row);
            }
        return cursor;
    }



    private int deleteGlobalDump() {
        Log.e(TAG, "Deleting Global dump");
        int deleteCount = -1;
        Message message = new Message();
        message.setMessageType(MessageType.GlobalDelete);
        message.setNodeDetails(myNodeDetails);
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, message);
        Log.e(TAG, "##################################################");
        Log.e(TAG, "Busy Waiting started...");
        while(globalDeleteCount < 0){
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Log.e(TAG,"***********************Exception in getGlobalDump...sleep()************************");
                e.printStackTrace();
            }
        }
        Log.e(TAG,"Busy Waiting finished...");
        deleteCount = globalDeleteCount;
        Log.e(TAG, "globalDeleteCount before assigning -1: " + globalDeleteCount);
        globalDeleteCount = -1;

        Log.e(TAG, "deleteCount inside deleteGlobalDump(): " + deleteCount);
        Log.e(TAG, "globalDeleteCount after assigning -1: " + globalDeleteCount);
        Log.e(TAG, "##################################################");

        return deleteCount;
    }


    private int deleteLocalDump() {
        int deleteCount = -1;
        SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        deleteCount = sharedPref.getAll().size();
        editor.clear();
        editor.commit();
        Log.e(TAG, "Values Deleted : " + deleteCount);
        return deleteCount;
    }


    private String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    private boolean lookup(String keyHash){
        boolean retVal = false;
        if(myNodeDetails.isFirstNode()){
            if (keyHash.compareTo(myNodeDetails.getPredecessorNodeIdHash()) > 0 ||
                    keyHash.compareTo(myNodeDetails.getNodeIdHash()) <= 0) {
                retVal = true;
            } else
                retVal = false;

        }else {
            if (keyHash.compareTo(myNodeDetails.getPredecessorNodeIdHash()) > 0 &&
                    keyHash.compareTo(myNodeDetails.getNodeIdHash()) <= 0) {
                retVal = true;
            } else
                retVal = false;
        }
        return retVal;
    }



}
