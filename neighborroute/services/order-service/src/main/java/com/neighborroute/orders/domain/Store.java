package com.neighborroute.orders.domain;

import jakarta.persistence.*;

@Entity
@Table(name="stores")
public class Store {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, length=150)
    private String name;

    @Column(nullable=false, length=300)
    private String address;

    protected Store() {

    }

    public Store(String name, String address){
        this.name=name;
        this.address=address;
    }

    public Long getId(){
        return id;
    }

    public String getName(){
        return name;
    }

    public String getAddress() {
        return address;
    }
}
