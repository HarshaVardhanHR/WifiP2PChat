package com.android.WifiP2PChat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.Toast;

import com.android.WifiP2PChat.AsyncTasks.SendMessageClient;
import com.android.WifiP2PChat.AsyncTasks.SendMessageServer;
import com.android.WifiP2PChat.CustomAdapters.ChatAdapter;
import com.android.WifiP2PChat.Entities.Image;
import com.android.WifiP2PChat.Entities.MediaFile;
import com.android.WifiP2PChat.Entities.Message;
import com.android.WifiP2PChat.Receivers.WifiDirectBroadcastReceiver;
import com.android.WifiP2PChat.util.ActivityUtilities;
import com.android.WifiP2PChat.util.FileUtilities;

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

public class ChatActivity extends Activity {
	private static final String TAG = "ChatActivity";
	private static final int PICK_IMAGE = 1;
	private static final int TAKE_PHOTO = 2;
	private static final int RECORD_AUDIO = 3;
	private static final int RECORD_VIDEO = 4;
	private static final int CHOOSE_FILE = 5;
	private static final int DRAWING = 6;
	private static final int DOWNLOAD_IMAGE = 100;
	private static final int DELETE_MESSAGE = 101;
	private static final int DOWNLOAD_FILE = 102;
	private static final int COPY_TEXT = 103;
	private static final int SHARE_TEXT = 104;
	private static final int REQUEST_PERMISSIONS_REQUIRED = 7;

	private WifiP2pManager mManager;
	private Channel mChannel;
	private WifiDirectBroadcastReceiver mReceiver;
	private IntentFilter mIntentFilter;
	private EditText edit;
	private static ListView listView;
	private static List<Message> listMessage;
	private static ChatAdapter chatAdapter;
	private Uri fileUri;
	private String fileURL;
	private ArrayList<Uri> tmpFilesUri;
	private Uri mPhotoUri;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_chat);

		mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(this, getMainLooper(), null);
        mReceiver = WifiDirectBroadcastReceiver.createInstance();
        mReceiver.setmActivity(this);

		String[] PERMISSIONS = {
				Manifest.permission.CAMERA,
				Manifest.permission.READ_EXTERNAL_STORAGE,
				Manifest.permission.WRITE_EXTERNAL_STORAGE,
				Manifest.permission.RECORD_AUDIO
		};

		if(!hasPermissions(this, PERMISSIONS)){
			ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS_REQUIRED);
		}

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        //Start the service to receive message
        startService(new Intent(this, MessageService.class));

        //Initialize the adapter for the chat
        listView = (ListView) findViewById(R.id.messageList);
        listMessage = new ArrayList<Message>();
        chatAdapter = new ChatAdapter(this, listMessage);
        listView.setAdapter(chatAdapter);

        //Initialize the list of temporary files URI
        tmpFilesUri = new ArrayList<Uri>();

		//Send a message
        Button button = (Button) findViewById(R.id.sendMessage);
        edit = (EditText) findViewById(R.id.editMessage);
        button.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if(!edit.getText().toString().equals("")){
//					Log.v(TAG, "Send message");
					sendMessage(Message.TEXT_MESSAGE);
				}
				else{
					Toast.makeText(ChatActivity.this, "Please enter a not empty message", Toast.LENGTH_SHORT).show();
				}
			}
		});

        //Register the context menu to the list view (for pop up menu)
        registerForContextMenu(listView);
	}

	public static boolean hasPermissions(Context context, String... permissions) {
		if (context != null && permissions != null) {
			for (String permission : permissions) {
				if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);

		ActivityUtilities.customiseActionBar(this);
	}

	@Override
    public void onResume() {
        super.onResume();
        registerReceiver(mReceiver, mIntentFilter);

		mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {

			@Override
			public void onSuccess() {
				Log.v(TAG, "Discovery process succeeded");
			}

			@Override
			public void onFailure(int reason) {
				Log.v(TAG, "Discovery process failed");
			}
		});
		saveStateForeground(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
        saveStateForeground(false);
    }

	@Override
	public void onBackPressed() {
		AlertDialog.Builder newDialog = new AlertDialog.Builder(this);
		newDialog.setTitle("Close chatroom");
		newDialog.setMessage("Are you sure you want to close this chatroom?\n"
				+ "You will no longer be able to receive messages, and "
				+ "all unsaved media files will be deleted.\n"
				+ "If you are the server, all other users will be disconnected as well.");

		newDialog.setPositiveButton("Yes", new DialogInterface.OnClickListener(){

			@Override
			public void onClick(DialogInterface dialog, int which) {
				clearTmpFiles(getExternalFilesDir(null));
				if(MainActivity.server!=null){
					MainActivity.server.interrupt();
				}
				android.os.Process.killProcess(android.os.Process.myPid());
			}

		});

		newDialog.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {

			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.cancel();
			}
		});

		newDialog.show();
	}

    @Override
	protected void onDestroy() {
		super.onDestroy();
		super.onStop();
		clearTmpFiles(getExternalFilesDir(null));
	}

	// Handle the data sent back by the 'for result' activities (pick/take image, record audio/video)
    @Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch(requestCode){

			case PICK_IMAGE:
				if (resultCode == RESULT_OK && data.getData() != null) {
					fileUri = data.getData();
					sendMessage(Message.IMAGE_MESSAGE);
//					Log.e(TAG,"kak1:"+fileUri);
				}
				break;
			case TAKE_PHOTO:
//				if (resultCode == RESULT_OK && data.getData() != null) {
//					fileUri = data.getData();
//					Log.e(TAG,"kak2:"+fileUri);
//					sendMessage(Message.IMAGE_MESSAGE);
//					tmpFilesUri.add(fileUri);
//					Log.e(TAG,"kak2:"+tmpFilesUri);}

				if (resultCode == RESULT_OK) {
					// Image saved to a generated MediaStore.Images.Media.EXTERNAL_CONTENT_URI
//					String[] projection = {
//							MediaStore.MediaColumns._ID,
//							MediaStore.Images.ImageColumns.ORIENTATION,
//							MediaStore.Images.Media.DATA
//					};

//					Cursor c = getContentResolver().query(mPhotoUri, projection, null, null, null);
//					if (c != null && c.getCount()>0) {
//						c.moveToFirst();
//						String photoFileName = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));

						fileUri = mPhotoUri;
						sendMessage(Message.IMAGE_MESSAGE);
						tmpFilesUri.add(fileUri);
//					}
//					c.close();

				} else if (resultCode == RESULT_CANCELED) {
					// User cancelled the image capture
				} else {
					// Image capture failed, advise user
				}

		break;
			case RECORD_AUDIO:
				if (resultCode == RESULT_OK) {
					fileURL = (String) data.getStringExtra("audioPath");
					sendMessage(Message.AUDIO_MESSAGE);
				}
				break;
			case RECORD_VIDEO:
				if (resultCode == RESULT_OK) {
					fileUri = data.getData();
					fileURL = MediaFile.getRealPathFromURI(this, fileUri);
					sendMessage(Message.VIDEO_MESSAGE);
				}
				break;
			case CHOOSE_FILE:
				if (resultCode == RESULT_OK) {
					fileURL = (String) data.getStringExtra("filePath");
					sendMessage(Message.FILE_MESSAGE);
				}
				break;
			case DRAWING:
				if(resultCode == RESULT_OK){
					fileURL = (String) data.getStringExtra("drawingPath");
					sendMessage(Message.DRAWING_MESSAGE);
				}
				break;
		}
	}
	public Uri getImageUri(Context inContext, Bitmap inImage) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		inImage.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
		String path = MediaStore.Images.Media.insertImage(inContext.getContentResolver(), inImage, "Title", null);
		return Uri.parse(path);
	}

	public String getRealPathFromURI(Uri uri) {
		Cursor cursor = getContentResolver().query(uri, null, null, null, null);
		cursor.moveToFirst();
		int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
		return cursor.getString(idx);
	}

	// Hydrate Message object then launch the AsyncTasks to send it
	public void sendMessage(int type){
//		Log.v(TAG, "Send message starts");
		// Message written in EditText is always sent
		Message mes = new Message(type, edit.getText().toString(), null, MainActivity.chatName);

		switch(type){
			case Message.IMAGE_MESSAGE:
				Image image = new Image(this, fileUri);
				Log.e(TAG, "Bitmap from url ok" + fileUri);
					mes.setByteArray(image.bitmapToByteArray(image.getBitmapFromUri()));
					mes.setFileName(image.getFileName());
					mes.setFileSize(image.getFileSize());
					Log.e(TAG, "Set byte array to image ok"+image.getFileSize()+"-"+image.getFileName());

				break;
			case Message.AUDIO_MESSAGE:
				MediaFile audioFile = new MediaFile(this, fileURL, Message.AUDIO_MESSAGE);
				mes.setByteArray(audioFile.fileToByteArray());
				mes.setFileName(audioFile.getFileName());
				mes.setFilePath(audioFile.getFilePath());
				break;
			case Message.VIDEO_MESSAGE:
				MediaFile videoFile = new MediaFile(this, fileURL, Message.AUDIO_MESSAGE);
				mes.setByteArray(videoFile.fileToByteArray());
				mes.setFileName(videoFile.getFileName());
				mes.setFilePath(videoFile.getFilePath());
				tmpFilesUri.add(fileUri);
				break;
			case Message.FILE_MESSAGE:
				MediaFile file = new MediaFile(this, fileURL, Message.FILE_MESSAGE);
				mes.setByteArray(file.fileToByteArray());
				mes.setFileName(file.getFileName());
				break;
			case Message.DRAWING_MESSAGE:
				MediaFile drawingFile = new MediaFile(this, fileURL, Message.DRAWING_MESSAGE);
				mes.setByteArray(drawingFile.fileToByteArray());
				mes.setFileName(drawingFile.getFileName());
				mes.setFilePath(drawingFile.getFilePath());
				break;
		}
//		Log.e(TAG, "Message object hydrated");

		// First cycle in tracking where this msg goes.
		// MARK: 16/06/2018 Once msg instantiated, get and records user chat name.
		mes.setUser_record(MainActivity.loadChatName(this));

//		Log.e(TAG, "Start AsyncTasks to send the message");

		if(mReceiver.isGroupeOwner() == WifiDirectBroadcastReceiver.IS_OWNER){
			Log.e(TAG, "Message hydrated, start SendMessageServer AsyncTask");

			new SendMessageServer(ChatActivity.this, true).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mes);
		}
		else if(mReceiver.isGroupeOwner() == WifiDirectBroadcastReceiver.IS_CLIENT){
			Log.e(TAG, "Message hydrated, start SendMessageClient AsyncTask");

			new SendMessageClient(ChatActivity.this, mReceiver.getOwnerAddr()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mes);
		}

		edit.setText("");
	}

	// Refresh the message list
	public static void refreshList(Message message, boolean isMine){
//		Log.v(TAG, "Refresh message list starts");

		message.setMine(isMine);
//		Log.e(TAG, "refreshList: message is from :"+message.getSenderAddress().getHostAddress() );
//		Log.e(TAG, "refreshList: message is from :"+isMine );
		listMessage.add(message);
    	chatAdapter.notifyDataSetChanged();

//    	Log.v(TAG, "Chat Adapter notified of the changes");

    	//Scroll to the last element of the list
    	listView.setSelection(listMessage.size() - 1);
    }

	// Save the app's state (foreground or background) to a SharedPrefereces
	public void saveStateForeground(boolean isForeground){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
  		Editor edit = prefs.edit();
  		edit.putBoolean("isForeground", isForeground);
  		edit.commit();
	}

	@Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chat, menu);
        return true;
    }

	// Handle click on the menu
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int idItem = item.getItemId();
        switch(idItem){
	        case R.id.send_image:
	        	showPopup(edit);
	        	return true;

	        case R.id.send_audio:
	        	Log.v(TAG, "Start activity to record audio");
	        	startActivityForResult(new Intent(this, RecordAudioActivity.class), RECORD_AUDIO);
	        	return true;

	        case R.id.send_video:
	        	Log.v(TAG, "Start activity to record video");
	        	Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
	        	takeVideoIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
	        	if (takeVideoIntent.resolveActivity(getPackageManager()) != null) {
	                startActivityForResult(takeVideoIntent, RECORD_VIDEO);
	            }
	        	return true;

	        case R.id.send_file:
	        	Log.v(TAG, "Start activity to choose file");
	        	Intent chooseFileIntent = new Intent(this, FilePickerActivity.class);
	        	startActivityForResult(chooseFileIntent, CHOOSE_FILE);
	        	return true;

	        case R.id.send_drawing:
	        	Log.v(TAG, "Start activity to draw");
	        	Intent drawIntent = new Intent(this, DrawingActivity.class);
	        	startActivityForResult(drawIntent, DRAWING);
	        	return true;

	        default:
	        	return super.onOptionsItemSelected(item);
        }
    }

    //Show the popup menu
    public void showPopup(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.setOnMenuItemClickListener(new OnMenuItemClickListener() {

			@Override
			public boolean onMenuItemClick(MenuItem item) {
				switch(item.getItemId()){
				case R.id.pick_image:
					Log.e(TAG, "Pick an image");
					Intent intent = new Intent(Intent.ACTION_PICK);
					intent.setType("image/*");
					intent.setAction(Intent.ACTION_GET_CONTENT);

					// Prevent crash if no app can handle the intent
					if (intent.resolveActivity(getPackageManager()) != null) {
						startActivityForResult(intent, PICK_IMAGE);
				    }
					break;

				case R.id.take_photo:
					Log.e(TAG, "Take a photo");
//					Intent intent2 = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
//
//					if (intent2.resolveActivity(getPackageManager()) != null) {
//						startActivityForResult(intent2, TAKE_PHOTO);
//				    }

					// Alternative strategy of generating a content URI using the MediaStore
					// This way seems to work very reliably

					mPhotoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new ContentValues());
					Intent intent4 = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
					intent4.putExtra(MediaStore.EXTRA_OUTPUT, mPhotoUri);
					startActivityForResult(intent4, TAKE_PHOTO);

					break;
				}
				return true;
			}
		});
        popup.inflate(R.menu.send_image);
        popup.show();
    }

    //Create pop up menu for image download, delete message, etc...
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        menu.setHeaderTitle("Options");

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        Message mes = listMessage.get((int) info.position);

        //Option to delete message independently of its type
        menu.add(0, DELETE_MESSAGE, Menu.NONE, "Delete message");

        if(!mes.getmText().equals("")){
        	//Option to copy message's text to clipboard
            menu.add(0, COPY_TEXT, Menu.NONE, "Copy message text");
            //Option to share message's text
        	menu.add(0, SHARE_TEXT, Menu.NONE, "Share message text");
        }

        int type = mes.getmType();
        switch(type){
        	case Message.IMAGE_MESSAGE:
        		menu.add(0, DOWNLOAD_IMAGE, Menu.NONE, "Download image");
        		break;
        	case Message.FILE_MESSAGE:
        		menu.add(0, DOWNLOAD_FILE, Menu.NONE, "Download file");
        		break;
        	case Message.AUDIO_MESSAGE:
        		menu.add(0, DOWNLOAD_FILE, Menu.NONE, "Download audio file");
        		break;
        	case Message.VIDEO_MESSAGE:
        		menu.add(0, DOWNLOAD_FILE, Menu.NONE, "Download video file");
        		break;
        	case Message.DRAWING_MESSAGE:
        		menu.add(0, DOWNLOAD_FILE, Menu.NONE, "Download drawing");
        		break;
        }
    }

    //Handle click event on the pop up menu
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case DOWNLOAD_IMAGE:
            	downloadImage(info.id);
                return true;

            case DELETE_MESSAGE:
            	deleteMessage(info.id);
            	return true;

            case DOWNLOAD_FILE:
            	downloadFile(info.id);
            	return true;

            case COPY_TEXT:
            	copyTextToClipboard(info.id);
            	return true;

            case SHARE_TEXT:
            	shareMedia(info.id, Message.TEXT_MESSAGE);
            	return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    //Download image and save it to Downloads
    public void downloadImage(long id){
    	Message mes = listMessage.get((int) id);
    	Bitmap bm = mes.byteArrayToBitmap(mes.getByteArray());
    	String path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();

    	FileUtilities.saveImageFromBitmap(this, bm, path, mes.getFileName());
    	FileUtilities.refreshMediaLibrary(this);
    }

    //Download file and save it to Downloads
    public void downloadFile(long id){
    	Message mes = listMessage.get((int) id);
    	String sourcePath = mes.getFilePath();
        String destinationPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();

        FileUtilities.copyFile(this, sourcePath, destinationPath, mes.getFileName());
        FileUtilities.refreshMediaLibrary(this);
    }

    //Delete a message from the message list (doesn't delete on other phones)
    public void deleteMessage(long id){
    	listMessage.remove((int) id);
    	chatAdapter.notifyDataSetChanged();
    }

    private void clearTmpFiles(File dir){
    	File[] childDirs = dir.listFiles();
    	for(File child : childDirs){
    		if(child.isDirectory()){
    			clearTmpFiles(child);
    		}
    		else{
    			child.delete();
    		}
    	}
    	for(Uri uri: tmpFilesUri){
    		getContentResolver().delete(uri, null, null);
    	}
    	FileUtilities.refreshMediaLibrary(this);
    }

    public void talkTo(String destination){
    	edit.setText("@" + destination + " : ");
    	edit.setSelection(edit.getText().length());
    }

    private void copyTextToClipboard(long id){
    	Message mes = listMessage.get((int) id);
		ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		ClipData clip = ClipData.newPlainText("message", mes.getmText());
		clipboard.setPrimaryClip(clip);
		Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void shareMedia(long id, int type){
    	Message mes = listMessage.get((int) id);

    	switch(type){
    		case Message.TEXT_MESSAGE:
				Intent sendIntent = new Intent();
    	    	sendIntent.setAction(Intent.ACTION_SEND);
    	    	sendIntent.putExtra(Intent.EXTRA_TEXT, mes.getmText());
    	    	sendIntent.setType("text/plain");
    	    	startActivity(sendIntent);
    	}
    }

	/** Create a File for saving an image or video */
	private static File getOutputMediaFile(int type){
		// To be safe, you should check that the SDCard is mounted
		// using Environment.getExternalStorageState() before doing this.

		File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_PICTURES), "MyCameraApp");
		// This location works best if you want the created images to be shared
		// between applications and persist after your app has been uninstalled.

		// Create the storage directory if it does not exist
		if (! mediaStorageDir.exists()){
			if (! mediaStorageDir.mkdirs()){
				Log.d("MyCameraApp", "failed to create directory");
				return null;
			}
		}

		// Create a media file name
		String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		File mediaFile;
		if (type == MEDIA_TYPE_IMAGE){
			mediaFile = new File(mediaStorageDir.getPath() + File.separator +
					"IMG_"+ timeStamp + ".jpg");
		} else if(type == MEDIA_TYPE_VIDEO) {
			mediaFile = new File(mediaStorageDir.getPath() + File.separator +
					"VID_"+ timeStamp + ".mp4");
		} else {
			return null;
		}

		return mediaFile;
	}

	/** Create a file Uri for saving an image or video */
	private static Uri getOutputMediaFileUri(int type){
		return Uri.fromFile(getOutputMediaFile(type));
	}
}
