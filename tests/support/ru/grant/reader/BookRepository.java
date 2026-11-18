package ru.grant.reader;
import java.io.*;
final class BookRepository {
    static final class Chapter {
        public String title;
        public int offset, level;
        Chapter(String title, int offset, int level) { this.title=title; this.offset=offset; this.level=level; }
    }
    static byte[] readLimited(InputStream input,int limit)throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
        while((n=input.read(buffer))!=-1){if(output.size()+n>limit)throw new IOException("Limit");output.write(buffer,0,n);}
        return output.toByteArray();
    }
}
