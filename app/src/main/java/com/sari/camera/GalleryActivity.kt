package com.sari.camera

import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity

class GalleryActivity: ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(list);setContentView(scroll)
  val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.MIME_TYPE)
  contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,projection,"${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",arrayOf("Pictures/SARI Camera/%"),"${MediaStore.Images.Media.DATE_ADDED} DESC")?.use{c->while(c.moveToNext()){val id=c.getLong(0);val name=c.getString(1);val mime=c.getString(2);val uri=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id);val item=Button(this).apply{text=name;setOnClickListener{startActivity(Intent(this@GalleryActivity,ViewerActivity::class.java).apply{data=uri;putExtra("mime",mime);putExtra("name",name)})}};list.addView(item,LinearLayout.LayoutParams(-1,64))}}
 }
}
